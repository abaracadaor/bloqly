package org.bloqly.machine.component

import org.bloqly.machine.Application
import org.bloqly.machine.Application.Companion.MAX_TRANSACTION_AGE
import org.bloqly.machine.model.Account
import org.bloqly.machine.model.Block
import org.bloqly.machine.model.FinalizedTransaction
import org.bloqly.machine.model.InvocationResult
import org.bloqly.machine.model.Properties
import org.bloqly.machine.model.Transaction
import org.bloqly.machine.model.TransactionOutput
import org.bloqly.machine.model.TransactionOutputId
import org.bloqly.machine.model.Vote
import org.bloqly.machine.repository.AccountRepository
import org.bloqly.machine.repository.BlockRepository
import org.bloqly.machine.repository.FinalizedTransactionRepository
import org.bloqly.machine.repository.PropertyService
import org.bloqly.machine.repository.TransactionOutputRepository
import org.bloqly.machine.repository.TransactionRepository
import org.bloqly.machine.repository.VoteRepository
import org.bloqly.machine.service.AccountService
import org.bloqly.machine.service.BlockService
import org.bloqly.machine.service.ContractService
import org.bloqly.machine.service.SpaceService
import org.bloqly.machine.service.TransactionService
import org.bloqly.machine.service.VoteService
import org.bloqly.machine.util.CryptoUtils
import org.bloqly.machine.util.ObjectUtils
import org.bloqly.machine.util.TimeUtils
import org.bloqly.machine.util.decode16
import org.bloqly.machine.vo.block.BlockData
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private data class TransactionResult(
    val transaction: Transaction,
    val invocationResult: InvocationResult
)

@Service
// TODO add more test for rejected transaction
class BlockProcessor(
    private val transactionService: TransactionService,
    private val voteService: VoteService,
    private val voteRepository: VoteRepository,
    private val blockService: BlockService,
    private val blockRepository: BlockRepository,
    private val transactionProcessor: TransactionProcessor,
    private val propertyService: PropertyService,
    private val contractService: ContractService,
    private val transactionOutputRepository: TransactionOutputRepository,
    private val accountRepository: AccountRepository,
    private val spaceService: SpaceService,
    private val transactionRepository: TransactionRepository,
    private val finalizedTransactionRepository: FinalizedTransactionRepository,
    private val accountService: AccountService
) {

    private val log: Logger = LoggerFactory.getLogger(BlockProcessor::class.simpleName)

    @Transactional
    fun processReceivedBlock(blockData: BlockData) {

        log.info("Processing received block: ${blockData.block}")

        val receivedBlock = blockData.block.toModel()

        requireValid(receivedBlock)

        val votes = blockData.votes.map { vote ->
            try {
                voteService.findVote(vote.publicKey, vote.blockHash)!!
            } catch (e: Exception) {
                log.error("Could not find saved vote $vote")
                throw e
            }
        }

        val transactions = blockData.transactions.map { tx ->
            try {
                transactionService.findByHash(tx.hash)!!
            } catch (e: Exception) {
                log.error("Could not find saved transaction $tx")
                throw e
            }
        }

        val propertyContext = PropertyContext(propertyService, contractService)

        evaluateFromLIB(receivedBlock, propertyContext)

        val block = receivedBlock.copy(
            transactions = transactions,
            votes = votes
        )

        evaluateBlock(block, propertyContext)

        moveLIBIfNeeded(saveBlock(block))
    }

    private fun saveBlock(block: Block): Block {

        require(blockService.isAcceptable(block)) {
            "Block is not acceptable ${block.header()}"
        }

        require(
            accountService.isProducerValidForRound(
                block.spaceId,
                block.producerId,
                block.round
            )
        ) {
            "Producer is invalid in block ${block.height}"
        }
        return blockRepository.save(block)
    }

    @Transactional
    fun evaluateFromLIB(toBlock: Block, propertyContext: PropertyContext) {
        getBlocksFromLIB(toBlock).forEach { block ->
            evaluateBlock(block, propertyContext)
        }
    }

    @Transactional(readOnly = true)
    fun getLastPropertyValue(
        spaceId: String,
        self: String,
        target: String,
        key: String
    ): ByteArray? {
        val lastBlock = blockService.getLastBlockBySpace(spaceId)

        val propertyContext = PropertyContext(propertyService, contractService)

        evaluateFromLIB(lastBlock, propertyContext)

        return propertyContext.getPropertyValue(spaceId, self, target, key)
    }

    /**
     * Returns blocks range (afterBlock, toBlock]
     */
    @Transactional(readOnly = true)
    internal fun getBlocksFromLIB(block: Block): List<Block> {

        var currentBlock = block

        val blocks = mutableListOf<Block>()

        while (currentBlock.height > block.libHeight) {
            blocks.add(currentBlock)

            currentBlock = blockRepository.findByHash(currentBlock.parentHash)!!
        }

        return blocks.reversed()
    }

    private fun evaluateBlock(block: Block, propertyContext: PropertyContext) {

        block.transactions.forEach { tx ->

            // already processed this transaction?
            val txOutput = transactionOutputRepository
                .findById(TransactionOutputId(block.hash, tx.hash))

            val output = if (txOutput.isPresent) {
                ObjectUtils.readProperties(txOutput.get().output)
            } else {
                val invocationResult = transactionProcessor.processTransaction(tx, propertyContext)

                require(invocationResult.isOK()) {
                    "Could not process transaction $tx"
                }

                saveTxOutputs(listOf(TransactionResult(tx, invocationResult)), block)

                invocationResult.output
            }

            propertyContext.updatePropertyValues(output)
        }
    }

    private fun requireValid(block: Block) {

        val producer = accountRepository.findByAccountId(block.producerId)!!

        require(CryptoUtils.verifyBlock(block, producer.publicKey.decode16())) {
            "Cold not verify block ${block.hash}"
        }
    }

    @Transactional
    fun createNextBlock(
        lastBlockHash: String,
        transactions: List<Transaction>,
        producer: Account,
        passphrase: String,
        round: Long
    ): BlockData {

        val timeStart = TimeUtils.getCurrentTime()

        val lastBlock = blockRepository.getByHash(lastBlockHash)

        log.info("Creating next block on top of ${lastBlock.header()}")

        val spaceId = lastBlock.spaceId

        // did I already create block in this round?
        // if yes - return it
        blockRepository.findBySpaceIdAndProducerIdAndRound(spaceId, producer.accountId, round)
            ?.let { return BlockData(it) }

        val newHeight = lastBlock.height + 1

        val propertyContext = PropertyContext(propertyService, contractService)

        evaluateFromLIB(lastBlock, propertyContext)

        val txResults = getTransactionResultsForNextBlock(transactions, propertyContext, timeStart)

        val selectedTransactions = txResults.map { it.transaction }

        val votes = getVotesForBlock(lastBlock.hash)
            // don't include own vote in block I produce - my block is my vote
            .filter { it.publicKey != producer.publicKey }
        val prevVotes = getVotesForBlock(lastBlock.parentHash)

        val diff = votes.minus(prevVotes).size
        val weight = lastBlock.weight + votes.size

        val newBlock = blockService.newBlock(
            spaceId = spaceId,
            height = newHeight,
            weight = weight,
            diff = diff,
            timestamp = TimeUtils.getCurrentTime(),
            parentHash = lastBlock.hash,
            producerId = producer.accountId,
            passphrase = passphrase,
            txHash = CryptoUtils.hashTransactions(selectedTransactions),
            validatorTxHash = CryptoUtils.hashVotes(votes),
            round = round,
            transactions = selectedTransactions,
            votes = votes
        )

        // todo add tx output hash to block and signature
        saveTxOutputs(txResults, newBlock)

        val blockData = BlockData(saveBlock(newBlock))

        moveLIBIfNeeded(newBlock)

        return blockData
    }

    // TODO validate block

    /**
     * Apply transaction outputs if LIB moved forward
     * Iterate and apply all transactions from the block next to the previous LIB including NEW_LIB
     * In some situations LIB.height + 1 = NEW_LIB.height
     */
    private fun moveLIBIfNeeded(lastBlock: Block) {

        log.info("Check if LIB has changed, last block: ${lastBlock.header()}")

        val parentBlock = blockService.getByHash(lastBlock.parentHash)

        if (parentBlock.libHeight == lastBlock.libHeight) {
            return
        }

        log.info("Moving LIB from height ${parentBlock.libHeight} to ${lastBlock.libHeight}.")

        getBlocksFromLIB(parentBlock)
            // TODO add this restriction to getBlocksFromLIB to get better performance
            .filter { it.height <= lastBlock.libHeight }
            .forEach { block ->
                block.transactions.forEach { tx ->

                    try {

                        val txOutput = transactionOutputRepository
                            .findById(TransactionOutputId(block.hash, tx.hash))
                            .orElseThrow()

                        val properties = ObjectUtils.readProperties(txOutput.output)

                        propertyService.updateProperties(properties)

                        finalizedTransactionRepository.save(
                            FinalizedTransaction(
                                transaction = tx,
                                block = block
                            )
                        )
                    } catch (e: Exception) {
                        val errorMessage =
                            "Could not process transaction output tx: ${tx.hash}, block: ${block.hash}"
                        log.warn(errorMessage, e)
                        throw RuntimeException(errorMessage, e)
                    }
                }
            }
    }

    private fun saveTxOutputs(txResults: List<TransactionResult>, block: Block) {
        val txOutputs = txResults.map {
            TransactionOutput(
                TransactionOutputId(block.hash, it.transaction.hash),
                ObjectUtils.writeValueAsString(
                    Properties(it.invocationResult.output)
                )
            )
        }

        transactionOutputRepository.saveAll(txOutputs)
    }

    private fun getTransactionResultsForNextBlock(
        transactions: List<Transaction>,
        propertyContext: PropertyContext,
        timeStart: Long
    ): List<TransactionResult> {

        val hashes = transactions.map { it.hash }

        if (hashes.size > hashes.distinct().size) {
            throw IllegalStateException("Transactions are not unique")
        }

        val txResults = mutableListOf<TransactionResult>()

        run txs@{
            transactions.forEach { tx ->
                val timeSpent = TimeUtils.getCurrentTime() - timeStart
                if (timeSpent - timeStart > Application.TX_TIMEOUT) {
                    log.warn("Finishing processing transactions as time spent is already over the limit: $timeSpent")
                    return@txs
                } else {
                    val localPropertyContext = propertyContext.getLocalCopy()

                    val result = transactionProcessor.processTransaction(tx, localPropertyContext)

                    if (result.isOK()) {
                        propertyContext.merge(localPropertyContext)
                        txResults.add(TransactionResult(tx, result))
                    }
                }
            }
        }

        return txResults.toMutableList()
    }

    private fun getVotesForBlock(blockHash: String): List<Vote> =
        voteRepository.findByBlockHash(blockHash)

    @Transactional(readOnly = true)
    fun getPendingTransactions(depth: Int = Application.MAX_REFERENCED_BLOCK_DEPTH): List<Transaction> =
        spaceService.findAll().flatMap {
            val lastBlock = blockRepository.getLastBlock(it.id)
            getPendingTransactions(lastBlock, depth)
        }

    @Transactional(readOnly = true)
    fun getPendingTransactions(
        lastBlock: Block,
        depth: Int = Application.MAX_REFERENCED_BLOCK_DEPTH
    ): List<Transaction> {

        val minHeight = lastBlock.libHeight - depth

        // Not finalized blocks, current branch
        val blocksAfterLIB = getBlocksFromLIB(lastBlock).mapNotNull { it.id }

        // Not finalized tx ids, current branch
        val txsAfterLIB = if (blocksAfterLIB.isNotEmpty()) {
            transactionRepository.getTransactionsByBlockIds(blocksAfterLIB)
        } else {
            listOf()
        }

        val pendingTransactions = transactionRepository.getPendingTransactionsBySpace(lastBlock.spaceId)

        val minTimestamp = lastBlock.timestamp - MAX_TRANSACTION_AGE

        return pendingTransactions.subtract(txsAfterLIB).filter { tx ->
            // TODO try to optimize it to avoid repeated calls to dbs
            val referencedBlock = blockService.findByHash(tx.referencedBlockHash)
            tx.timestamp >= minTimestamp && referencedBlock != null && referencedBlock.height > minHeight
        }
    }
}
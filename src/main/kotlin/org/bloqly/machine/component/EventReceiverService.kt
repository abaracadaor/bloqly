package org.bloqly.machine.component

import org.bloqly.machine.service.AccountService
import org.bloqly.machine.service.BlockService
import org.bloqly.machine.service.SpaceService
import org.bloqly.machine.service.TransactionService
import org.bloqly.machine.util.TimeUtils
import org.bloqly.machine.vo.BlockData
import org.bloqly.machine.vo.TransactionRequest
import org.bloqly.machine.vo.TransactionVO
import org.bloqly.machine.vo.VoteVO
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * The main purpose of this class is to do basic preparations for requests handling
 * such as converting to model etc
 * TODO it should be reviewed as there is seem to be clash of functionality with EventProcessorService
 */
@Service
class EventReceiverService(
    private val eventProcessorService: EventProcessorService,
    private val accountService: AccountService,
    private val transactionService: TransactionService,
    private val blockService: BlockService,
    private val objectFilterService: ObjectFilterService,
    private val spaceService: SpaceService
) {
    private val log = LoggerFactory.getLogger(EventReceiverService::class.simpleName)

    fun receiveTransactionRequest(transactionRequest: TransactionRequest): TransactionVO {

        val lastBlock = blockService.getLastBlockForSpace(transactionRequest.space)

        val tx = transactionService.createTransaction(transactionRequest, lastBlock.libHash)

        return tx.toVO()
    }

    fun receiveTransactions(transactionVOs: List<TransactionVO>) {
        transactionVOs.forEach { tx ->
            if (!objectFilterService.mightContain(tx.hash)) {
                eventProcessorService.onTransaction(tx.toModel())
                objectFilterService.add(tx.hash)
            }
        }
    }

    fun receiveVotes(voteVOs: List<VoteVO>) {
        voteVOs.forEach { vote ->
            try {
                if (!objectFilterService.mightContain(vote.getUID())) {
                    val validator = accountService.ensureExistsAndGetByPublicKey(vote.publicKey)
                    eventProcessorService.onVote(vote.toModel(validator))
                    objectFilterService.add(vote.getUID())
                }
            } catch (e: Exception) {
                log.error(e.message, e)
            }
        }
    }

    fun onBlocks(proposals: List<BlockData>) {

        val round = TimeUtils.getCurrentRound()

        val spaceIds = spaceService.getSpaceIds()

        proposals
            .filter { it.block.round == round }
            .filter { it.block.spaceId in spaceIds }
            .filter { !objectFilterService.mightContain(it.block.hash) }
            .filter {
                val space = spaceService.findById(it.block.spaceId)!!
                val activeValidator = accountService.getProducerBySpace(space, round)
                activeValidator.accountId == it.block.producerId
            }
            .filter { blockService.isAcceptable(it.block.toModel()) }
            .forEach { blockData ->

                receiveTransactions(blockData.transactions)
                receiveVotes(blockData.votes)

                objectFilterService.add(blockData.block.hash)
                eventProcessorService.onProposal(blockData)
            }
    }
}
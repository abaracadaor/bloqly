package org.bloqly.machine.component

import org.bloqly.machine.service.AccountService
import org.bloqly.machine.service.BlockService
import org.bloqly.machine.service.TransactionService
import org.bloqly.machine.vo.BlockData
import org.bloqly.machine.vo.TransactionRequest
import org.bloqly.machine.vo.TransactionVO
import org.bloqly.machine.vo.VoteVO
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * The main purpose of this class is to do basic preparations for requests handling
 * such as converting to model etc
 */
@Service
class EventReceiverService(
    private val eventProcessorService: EventProcessorService,
    private val accountService: AccountService,
    private val transactionService: TransactionService,
    private val blockService: BlockService,
    private val objectFilterService: ObjectFilterService
) {
    private val log = LoggerFactory.getLogger(EventReceiverService::class.simpleName)

    fun receiveTransactions(transactionVOs: List<TransactionVO>) {
        transactionVOs.forEach {
            if (!objectFilterService.mightContain(it.hash)) {
                eventProcessorService.onTransaction(it.toModel())
                objectFilterService.add(it.hash)
            }
        }
    }

    fun receiveTransactionRequest(transactionRequest: TransactionRequest): TransactionVO {

        val lastBlock = blockService.getLastBlockForSpace(transactionRequest.space)

        val tx = transactionService.createTransaction(transactionRequest, lastBlock.libHash)

        return tx.toVO()
    }

    fun receiveVotes(voteVOs: List<VoteVO>) {
        voteVOs.forEach { vote ->
            try {
                val validator = accountService.ensureExistsAndGetByPublicKey(vote.publicKey)
                eventProcessorService.onVote(vote.toModel(validator))
            } catch (e: Exception) {
                val errorMessage = "Could not process vote $vote"
                log.warn(errorMessage)
                log.error(errorMessage, e)
            }
        }
    }

    fun receiveProposals(proposals: List<BlockData>) {
        eventProcessorService.onProposals(proposals.filter {
            !objectFilterService.mightContain(it.block.hash)
        })
    }
}
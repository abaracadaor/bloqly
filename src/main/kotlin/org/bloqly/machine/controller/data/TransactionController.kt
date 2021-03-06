package org.bloqly.machine.controller.data

import io.swagger.annotations.Api
import io.swagger.annotations.ApiOperation
import org.bloqly.machine.component.BlockProcessor
import org.bloqly.machine.component.EventReceiverService
import org.bloqly.machine.vo.node.NodeList
import org.bloqly.machine.vo.transaction.TransactionList
import org.bloqly.machine.vo.transaction.TransactionRequest
import org.bloqly.machine.vo.transaction.TransactionVO
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.servlet.http.HttpServletRequest

@Api(
    value = "/api/v1/data/data/transactions",
    description = "Operations on transactions",
    consumes = "application/json",
    produces = "application/json"
)
@Profile("server")
@RestController
@RequestMapping("/api/v1/data/transactions")
class TransactionController(
    private val eventReceiverService: EventReceiverService,
    private val blockProcessor: BlockProcessor
) {

    @ApiOperation(
        value = "Creates a new transaction",
        nickname = "onCreateTransaction",
        response = NodeList::class
    )
    @PostMapping()
    fun onCreateTransaction(@RequestBody transactionRequest: TransactionRequest): TransactionVO {
        return eventReceiverService.receiveTransactionRequest(transactionRequest)
    }

    @ApiOperation(
        value = "Search for list of pending transactions",
        nickname = "searchPendingTransactions",
        response = NodeList::class
    )
    @PostMapping("/search")
    fun searchPendingTransactions(request: HttpServletRequest): TransactionList {
        val transactions = blockProcessor.getPendingTransactions()

        return TransactionList.fromTransactions(transactions)
    }
}
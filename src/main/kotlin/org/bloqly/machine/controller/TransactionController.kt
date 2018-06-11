package org.bloqly.machine.controller

import org.bloqly.machine.component.EventReceiverService
import org.bloqly.machine.service.TransactionService
import org.bloqly.machine.vo.TransactionList
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Profile("server")
@RestController
@RequestMapping("/transactions")
class TransactionController(
    private val eventReceiverService: EventReceiverService,
    private val transactionService: TransactionService
) {

    @PostMapping
    fun onTransactions(@RequestBody transactionsList: TransactionList) {

        eventReceiverService.receiveTransactions(transactionsList.transactions)
    }

    @GetMapping
    fun getTransactions(): TransactionList {

        val transactions = transactionService.getNewTransactions()

        return TransactionList.fromTransactions(transactions)
    }
}
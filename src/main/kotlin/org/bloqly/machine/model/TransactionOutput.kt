package org.bloqly.machine.model

import javax.persistence.Column
import javax.persistence.EmbeddedId
import javax.persistence.Entity

@Entity
data class TransactionOutput(

    @EmbeddedId
    val transactionOutputId: TransactionOutputId,

    @Column(nullable = false, columnDefinition = "text")
    val output: String
)
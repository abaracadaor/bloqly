package org.bloqly.machine.model

import org.bloqly.machine.util.encode64
import org.bloqly.machine.vo.TransactionVO
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType.AUTO
import javax.persistence.Id
import javax.persistence.Lob
import javax.persistence.Table

@Entity
@Table(name = "transaction")
data class Transaction(

    @Id
    @GeneratedValue(strategy = AUTO)
    var id: Long? = null,

    @Column(nullable = false)
    val spaceId: String,

    @Column(nullable = false)
    val origin: String,

    @Column(nullable = false)
    val destination: String,

    @Column(nullable = false)
    var self: String,

    @Column(nullable = true)
    var key: String? = null,

    @Lob
    @Column(nullable = false)
    val value: ByteArray,

    @Column(nullable = false)
    val transactionType: TransactionType,

    @Column(nullable = false)
    val referencedBlockHash: String,

    @Column(nullable = false)
    val timestamp: Long,

    @Column(nullable = false)
    val signature: ByteArray,

    @Column(nullable = false)
    val publicKey: String,

    @Column(nullable = false)
    val hash: String
) {

    fun toVO(): TransactionVO {

        return TransactionVO(
            space = spaceId,
            destination = destination,
            self = self,
            key = key,
            value = value.encode64(),
            transactionType = transactionType,
            referencedBlockHash = referencedBlockHash,
            timestamp = timestamp,
            signature = signature.encode64(),
            publicKey = publicKey,
            hash = hash
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Transaction

        if (id != other.id) return false

        return true
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}

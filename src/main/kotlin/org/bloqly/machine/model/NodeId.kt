package org.bloqly.machine.model

import java.io.Serializable
import javax.persistence.Embeddable

@Embeddable
data class NodeId(

    val host: String,

    val port: Int

) : Serializable {

    override fun toString(): String {
        return "$host:$port"
    }
}
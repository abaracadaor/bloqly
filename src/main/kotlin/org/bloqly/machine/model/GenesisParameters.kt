package org.bloqly.machine.model

import org.bloqly.machine.annotation.ValueObject

@ValueObject
data class GenesisParameters(val parameters: List<GenesisParameter>)
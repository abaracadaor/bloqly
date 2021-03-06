package org.bloqly.machine.service

import org.bloqly.machine.model.Contract
import org.bloqly.machine.repository.ContractRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ContractService(
    private val contractRepository: ContractRepository
) {
    @Transactional(readOnly = true)
    fun findById(self: String): Contract? =
        contractRepository.findById(self).orElse(null)

    @Transactional
    fun saveAll(contracts: List<Contract>) {
        contractRepository.saveAll(contracts)
    }

    @Transactional
    fun findAll(): List<Contract> =
        contractRepository.findAll().toList()
}
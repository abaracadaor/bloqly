package org.bloqly.machine.component

import org.bloqly.machine.Application
import org.bloqly.machine.model.PropertyContext
import org.bloqly.machine.model.Space
import org.bloqly.machine.model.TransactionType
import org.bloqly.machine.repository.BlockRepository
import org.bloqly.machine.repository.PropertyService
import org.bloqly.machine.repository.SpaceRepository
import org.bloqly.machine.service.BlockService
import org.bloqly.machine.service.ContractExecutorService
import org.bloqly.machine.service.ContractService
import org.bloqly.machine.service.TransactionService
import org.bloqly.machine.util.CryptoUtils
import org.bloqly.machine.util.FileUtils
import org.bloqly.machine.util.encode16
import org.springframework.stereotype.Service
import java.io.File
import java.time.Instant

@Service
class BlockchainService(
    private val blockService: BlockService,
    private val contractExecutorService: ContractExecutorService,
    private val propertyService: PropertyService,
    private val contractService: ContractService,
    private val spaceRepository: SpaceRepository,
    private val transactionService: TransactionService,
    private val blockRepository: BlockRepository,
    private val transactionProcessor: TransactionProcessor
) {
    fun createBlockchain(spaceId: String, baseDir: String) {

        blockService.ensureSpaceEmpty(spaceId)

        val contractBody = File(baseDir).list()
            .filter {
                it.endsWith(".js")
            }
            .map { fileName ->
                val source = File("$baseDir/$fileName").readText()
                val extension = fileName.substringAfterLast(".")
                val header = FileUtils.getResourceAsString("/headers/header.$extension")
                header + source
            }.reduce { str, acc -> str + "\n" + acc }

        val initProperties = contractExecutorService.invokeFunction("init", contractBody)

        val rootId = initProperties.find { it.key == "root" }!!.value.toString()

        propertyService.updateProperties(spaceId, Application.DEFAULT_SELF, initProperties)

        spaceRepository.save(Space(id = spaceId, creatorId = rootId))

        val timestamp = Instant.now().toEpochMilli()

        val height = 0L
        val validatorTxHash = ByteArray(0)
        val contractBodyHash = CryptoUtils.hash(contractBody).encode16()

        val firstBlock = blockService.newBlock(
            spaceId = spaceId,
            height = height,
            weight = 0,
            diff = 0,
            timestamp = timestamp,
            parentId = contractBodyHash,
            producerId = rootId,
            txHash = null,
            validatorTxHash = validatorTxHash
        )

        val transaction = transactionService.createTransaction(
            space = spaceId,
            originId = rootId,
            destinationId = Application.DEFAULT_SELF,
            self = Application.DEFAULT_SELF,
            key = null,
            value = contractBody.toByteArray(),
            transactionType = TransactionType.CREATE,
            referencedBlockId = firstBlock.id,
            containingBlockId = firstBlock.id,
            timestamp = timestamp
        )

        val propertyContext = PropertyContext(propertyService, contractService)
        transactionProcessor.processTransaction(transaction, propertyContext)
        propertyContext.commit()

        firstBlock.txHash = CryptoUtils.digestTransactions(listOf(transaction))
        blockRepository.save(firstBlock)
    }
}
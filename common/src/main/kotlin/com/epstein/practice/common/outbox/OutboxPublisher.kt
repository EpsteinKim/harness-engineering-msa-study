package com.epstein.practice.common.outbox

import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class OutboxPublisher(
    private val outboxRepository: OutboxRepository,
    private val kafkaTemplate: KafkaTemplate<String, Any>,
) {
    private val logger = LoggerFactory.getLogger(OutboxPublisher::class.java)

    @Scheduled(fixedDelay = 1000)
    @Transactional
    fun relay() {
        val pending = outboxRepository.findPendingForUpdate(100)
        if (pending.isEmpty()) return

        for (event in pending) {
            kafkaTemplate.send(event.topic, event.key ?: "", event.payload)
        }
        outboxRepository.deleteAllInBatch(pending)

        logger.info("Outbox relay: {} events published", pending.size)
    }
}

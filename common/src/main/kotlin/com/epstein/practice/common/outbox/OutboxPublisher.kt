package com.epstein.practice.common.outbox

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.kafka.support.mapping.DefaultJacksonJavaTypeMapper
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.CompletableFuture

@Component
class OutboxPublisher(
    private val outboxRepository: OutboxRepository,
    @Value("\${spring.kafka.bootstrap-servers}") bootstrapServers: String,
) {
    private val logger = LoggerFactory.getLogger(OutboxPublisher::class.java)

    // Outbox 전용: StringSerializer로 raw JSON 전송 (이중 인코딩 방지)
    private val kafkaTemplate: KafkaTemplate<String, String> = KafkaTemplate(
        DefaultKafkaProducerFactory(
            mapOf<String, Any>(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                ProducerConfig.ACKS_CONFIG to "1",
                ProducerConfig.LINGER_MS_CONFIG to 5,
                ProducerConfig.BATCH_SIZE_CONFIG to 32768,
            )
        )
    )

@Scheduled(fixedDelay = 500)
    @Transactional
    fun relay() {
        val pending = outboxRepository.findPendingForUpdate(5000)
        if (pending.isEmpty()) return

        val futures = mutableListOf<Pair<OutboxEvent, CompletableFuture<SendResult<String, String>>>>()
        for (event in pending) {
            val record = ProducerRecord(
                event.topic,
                null as Int?,
                event.key ?: "",
                event.payload,
            )
            record.headers().add(
                RecordHeader(
                    DefaultJacksonJavaTypeMapper.DEFAULT_CLASSID_FIELD_NAME,
                    event.eventType.toByteArray()
                )
            )
            futures.add(event to kafkaTemplate.send(record))
        }

        val sent = mutableListOf<OutboxEvent>()
        for ((event, future) in futures) {
            try {
                future.get()
                sent.add(event)
            } catch (e: Exception) {
                logger.error("Outbox relay failed for topic={}, key={}: {}", event.topic, event.key, e.message)
            }
        }

        if (sent.isNotEmpty()) {
            outboxRepository.deleteAllInBatch(sent)
            logger.info("Outbox relay: {} events published", sent.size)
        }
    }
}

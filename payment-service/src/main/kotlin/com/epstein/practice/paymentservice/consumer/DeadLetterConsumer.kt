package com.epstein.practice.paymentservice.consumer

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class DeadLetterConsumer {

    private val logger = LoggerFactory.getLogger(DeadLetterConsumer::class.java)

    @KafkaListener(
        topics = [
            "seat.events.DLT",
            "payment.events.DLT",
            "payment.commands.DLT",
        ],
        groupId = "payment-service-dlt",
    )
    fun onDeadLetter(record: ConsumerRecord<String, Any>) {
        val originalTopic = String(record.headers().lastHeader("kafka_dlt-original-topic")?.value() ?: byteArrayOf())
        val exceptionMessage = String(record.headers().lastHeader("kafka_dlt-exception-message")?.value() ?: byteArrayOf())

        logger.error(
            "Dead letter received: originalTopic={}, key={}, value={}, exception={}",
            originalTopic, record.key(), record.value(), exceptionMessage
        )
    }
}

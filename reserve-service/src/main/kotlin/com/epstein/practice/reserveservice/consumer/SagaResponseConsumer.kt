package com.epstein.practice.reserveservice.consumer

import com.epstein.practice.common.event.PaymentCancelled
import com.epstein.practice.common.event.PaymentCreated
import com.epstein.practice.common.event.PaymentExpired
import com.epstein.practice.common.event.PaymentFailed
import com.epstein.practice.common.event.PaymentSucceeded
import com.epstein.practice.reserveservice.config.KafkaConfig
import com.epstein.practice.reserveservice.main.service.SagaOrchestrator
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
@KafkaListener(topics = [KafkaConfig.TOPIC_PAYMENT_EVENTS])
class SagaResponseConsumer(
    private val sagaOrchestrator: SagaOrchestrator,
) {
    private val logger = LoggerFactory.getLogger(SagaResponseConsumer::class.java)

    @KafkaHandler
    fun onPaymentCreated(event: PaymentCreated) {
        logger.info("PaymentCreated received: sagaId={}, paymentId={}", event.sagaId, event.paymentId)
        sagaOrchestrator.onPaymentCreated(event.sagaId, event.paymentId)
    }

    @KafkaHandler
    fun onPaymentSucceeded(event: PaymentSucceeded) {
        logger.info("PaymentSucceeded received: sagaId={}", event.sagaId)
        sagaOrchestrator.onPaymentSucceeded(event.sagaId, event.paymentId)
    }

    @KafkaHandler
    fun onPaymentFailed(event: PaymentFailed) {
        logger.info("PaymentFailed received: sagaId={}, reason={}", event.sagaId, event.reason)
        sagaOrchestrator.onPaymentFailed(event.sagaId, event.reason)
    }

    @KafkaHandler
    fun onPaymentExpired(event: PaymentExpired) {
        logger.info("PaymentExpired received: sagaId={}", event.sagaId)
        sagaOrchestrator.onTimeout(event.sagaId)
    }

    @KafkaHandler
    fun onPaymentCancelled(event: PaymentCancelled) {
        logger.info("PaymentCancelled received: sagaId={}", event.sagaId)
        sagaOrchestrator.onCancel(event.sagaId)
    }
}

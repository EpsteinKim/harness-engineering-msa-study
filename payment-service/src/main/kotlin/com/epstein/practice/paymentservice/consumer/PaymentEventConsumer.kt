package com.epstein.practice.paymentservice.consumer

import com.epstein.practice.common.event.PaymentCancelled
import com.epstein.practice.common.event.PaymentExpired
import com.epstein.practice.common.event.PaymentFailed
import com.epstein.practice.common.event.PaymentRequested
import com.epstein.practice.common.event.PaymentSucceeded
import com.epstein.practice.paymentservice.config.KafkaConfig
import com.epstein.practice.paymentservice.producer.PaymentProcessingService
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * `payment.events` 토픽 consumer (payment-service 측).
 *
 * - PaymentRequested → 실제 결제 처리 → 결과 이벤트 재발행
 * - 나머지(Succeeded/Failed/Expired/Cancelled) → 자신이 발행자, 소비 무시
 */
@Component
@KafkaListener(topics = [KafkaConfig.TOPIC_PAYMENT_EVENTS])
class PaymentEventConsumer(
    private val processingService: PaymentProcessingService
) {
    private val logger = LoggerFactory.getLogger(PaymentEventConsumer::class.java)

    @KafkaHandler
    fun onRequested(event: PaymentRequested) {
        logger.info("PaymentRequested received: seat={}, user={}", event.seatId, event.userId)
        processingService.process(event)
    }

    @KafkaHandler
    fun onSucceeded(event: PaymentSucceeded) { /* self-published, ignore */ }

    @KafkaHandler
    fun onFailed(event: PaymentFailed) { /* self-published, ignore */ }

    @KafkaHandler
    fun onExpired(event: PaymentExpired) { /* self-published, ignore */ }

    @KafkaHandler
    fun onCancelled(event: PaymentCancelled) { /* self-published, ignore */ }
}

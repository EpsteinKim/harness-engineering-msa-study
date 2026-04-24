package com.epstein.practice.paymentservice.consumer

import com.epstein.practice.common.event.PaymentCancelled
import com.epstein.practice.common.event.PaymentCreated
import com.epstein.practice.common.event.PaymentExpired
import com.epstein.practice.common.event.PaymentFailed
import com.epstein.practice.common.event.PaymentSucceeded
import com.epstein.practice.paymentservice.config.KafkaConfig
import org.springframework.kafka.annotation.KafkaHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * `payment.events` 토픽 consumer (payment-service 측 self-consume).
 *
 * 결제 처리는 payment.commands → PaymentCommandConsumer가 담당.
 * 여기서는 자기 발행 이벤트를 소비하되 추가 액션 없음.
 */
@Component
@KafkaListener(topics = [KafkaConfig.TOPIC_PAYMENT_EVENTS])
class PaymentEventConsumer {

    @KafkaHandler
    fun onCreated(event: PaymentCreated) { /* self-published */ }

    @KafkaHandler
    fun onSucceeded(event: PaymentSucceeded) { /* self-published */ }

    @KafkaHandler
    fun onFailed(event: PaymentFailed) { /* self-published */ }

    @KafkaHandler
    fun onExpired(event: PaymentExpired) { /* self-published */ }

    @KafkaHandler
    fun onCancelled(event: PaymentCancelled) { /* self-published */ }
}

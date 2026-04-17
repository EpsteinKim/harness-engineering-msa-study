package com.epstein.practice.paymentservice.service

import com.epstein.practice.common.event.PaymentFailed
import com.epstein.practice.common.event.PaymentRequested
import com.epstein.practice.common.event.PaymentSucceeded
import com.epstein.practice.paymentservice.config.KafkaConfig
import com.epstein.practice.paymentservice.constant.PaymentMethod
import com.epstein.practice.paymentservice.entity.PaymentStatus
import com.epstein.practice.paymentservice.repository.PaymentRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * PaymentRequested 이벤트를 받아 실제 결제 처리.
 * 기존 Payment(PENDING)를 조회해 상태 전이 → 결과 이벤트 발행.
 */
@Service
class PaymentProcessingService(
    private val paymentRepository: PaymentRepository,
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    @Value("\${payment.success-rate:0.7}") private val successRate: Double,
    private val random: Random = Random.Default,
) {
    private val logger = LoggerFactory.getLogger(PaymentProcessingService::class.java)

    @Transactional
    fun process(request: PaymentRequested) {
        val payment = paymentRepository.findBySeatIdAndStatus(request.seatId, PaymentStatus.PENDING)
            ?: run {
                logger.warn("No pending payment for seat {}, ignoring PaymentRequested", request.seatId)
                return
            }

        if (request.method !in PaymentMethod.ALL) {
            payment.status = PaymentStatus.FAILED
            payment.completedAt = LocalDateTime.now()
            logger.info("Invalid method, payment FAILED: seat={}, method={}", request.seatId, request.method)
            kafkaTemplate.send(
                KafkaConfig.TOPIC_PAYMENT_EVENTS, request.seatId.toString(),
                PaymentFailed(
                    seatId = request.seatId,
                    userId = request.userId,
                    paymentId = payment.id,
                    reason = "INVALID_METHOD"
                )
            )
            return
        }

        payment.method = request.method
        val success = random.nextDouble() < successRate
        payment.status = if (success) PaymentStatus.SUCCEEDED else PaymentStatus.FAILED
        payment.completedAt = LocalDateTime.now()

        logger.info("Payment processed: id={}, seat={}, user={}, status={}",
            payment.id, payment.seatId, payment.userId, payment.status)

        val resultEvent: Any = if (success) {
            PaymentSucceeded(
                seatId = request.seatId,
                userId = request.userId,
                paymentId = payment.id
            )
        } else {
            PaymentFailed(
                seatId = request.seatId,
                userId = request.userId,
                paymentId = payment.id,
                reason = "RANDOM_FAILURE"
            )
        }
        kafkaTemplate.send(KafkaConfig.TOPIC_PAYMENT_EVENTS, request.seatId.toString(), resultEvent)
    }
}

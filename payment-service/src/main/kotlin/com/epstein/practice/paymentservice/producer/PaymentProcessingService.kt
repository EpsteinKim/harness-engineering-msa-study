package com.epstein.practice.paymentservice.producer

import com.epstein.practice.common.event.PaymentFailed
import com.epstein.practice.common.event.PaymentSucceeded
import com.epstein.practice.common.event.ProcessPaymentCommand
import com.epstein.practice.paymentservice.config.KafkaConfig
import com.epstein.practice.paymentservice.type.constant.PaymentMethod
import com.epstein.practice.paymentservice.type.entity.PaymentStatus
import com.epstein.practice.paymentservice.main.repository.PaymentRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import com.epstein.practice.common.outbox.OutboxService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZonedDateTime
import kotlin.random.Random

@Service
class PaymentProcessingService(
    private val paymentRepository: PaymentRepository,
    private val outboxService: OutboxService,
    @Value("\${payment.success-rate:0.7}") private val successRate: Double,
    private val random: Random = Random.Default,
) {
    private val logger = LoggerFactory.getLogger(PaymentProcessingService::class.java)

    @Transactional
    fun process(command: ProcessPaymentCommand) {
        val payment = paymentRepository.findBySeatIdAndStatus(command.seatId, PaymentStatus.PENDING)
            ?: run {
                logger.warn("No pending payment for seat {}, ignoring", command.seatId)
                return
            }

        if (command.method !in PaymentMethod.ALL) {
            payment.status = PaymentStatus.FAILED
            payment.completedAt = ZonedDateTime.now()
            logger.info("Invalid method, payment FAILED: seat={}, method={}", command.seatId, command.method)
            outboxService.save(
                KafkaConfig.TOPIC_PAYMENT_EVENTS, command.seatId.toString(),
                PaymentFailed(
                    sagaId = command.sagaId,
                    seatId = command.seatId,
                    userId = command.userId,
                    paymentId = payment.id,
                    reason = "INVALID_METHOD"
                )
            )
            return
        }

        payment.method = command.method
        val success = random.nextDouble() < successRate
        payment.status = if (success) PaymentStatus.SUCCEEDED else PaymentStatus.FAILED
        payment.completedAt = ZonedDateTime.now()

        logger.info("Payment processed: id={}, seat={}, status={}", payment.id, payment.seatId, payment.status)

        val resultEvent: Any = if (success) {
            PaymentSucceeded(
                sagaId = command.sagaId,
                seatId = command.seatId,
                userId = command.userId,
                paymentId = payment.id,
            )
        } else {
            PaymentFailed(
                sagaId = command.sagaId,
                seatId = command.seatId,
                userId = command.userId,
                paymentId = payment.id,
                reason = "RANDOM_FAILURE"
            )
        }
        outboxService.save(KafkaConfig.TOPIC_PAYMENT_EVENTS, command.seatId.toString(), resultEvent)
    }
}

package com.epstein.practice.paymentservice.producer

import com.epstein.practice.common.event.PaymentCancelled
import com.epstein.practice.common.event.PaymentExpired
import com.epstein.practice.paymentservice.config.KafkaConfig
import com.epstein.practice.paymentservice.type.entity.PaymentStatus
import com.epstein.practice.paymentservice.main.repository.PaymentRepository
import org.slf4j.LoggerFactory
import com.epstein.practice.common.outbox.OutboxService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZonedDateTime

@Service
class PaymentTerminationService(
    private val paymentRepository: PaymentRepository,
    private val outboxService: OutboxService,
) {
    private val logger = LoggerFactory.getLogger(PaymentTerminationService::class.java)

    @Transactional
    fun expirePending(seatId: Long, sagaId: Long) {
        val payment = paymentRepository.findBySeatIdAndStatus(seatId, PaymentStatus.PENDING) ?: return
        payment.status = PaymentStatus.EXPIRED
        payment.completedAt = ZonedDateTime.now()
        logger.info("Payment EXPIRED: id={}, seatId={}", payment.id, seatId)
        outboxService.save(
            KafkaConfig.TOPIC_PAYMENT_EVENTS, seatId.toString(),
            PaymentExpired(sagaId = sagaId, seatId = seatId, paymentId = payment.id)
        )
    }

    @Transactional
    fun cancelPending(seatId: Long, sagaId: Long) {
        val payment = paymentRepository.findBySeatIdAndStatus(seatId, PaymentStatus.PENDING) ?: return
        payment.status = PaymentStatus.CANCELLED
        payment.completedAt = ZonedDateTime.now()
        logger.info("Payment CANCELLED: id={}, seatId={}", payment.id, seatId)
        outboxService.save(
            KafkaConfig.TOPIC_PAYMENT_EVENTS, seatId.toString(),
            PaymentCancelled(sagaId = sagaId, seatId = seatId, paymentId = payment.id)
        )
    }
}

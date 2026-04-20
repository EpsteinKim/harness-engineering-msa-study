package com.epstein.practice.paymentservice.consumer

import com.epstein.practice.common.event.HoldExpired
import com.epstein.practice.common.event.SeatHeld
import com.epstein.practice.common.event.SeatReleaseReason
import com.epstein.practice.common.event.SeatReleased
import com.epstein.practice.common.event.SeatReserved
import com.epstein.practice.paymentservice.config.KafkaConfig
import com.epstein.practice.paymentservice.main.service.PaymentService
import com.epstein.practice.paymentservice.producer.PaymentTerminationService
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * `seat.events` 토픽 consumer.
 *
 * - SeatHeld → Payment(PENDING) 생성 (Phase B)
 * - HoldExpired → Payment → EXPIRED 전이 (Phase D)
 * - SeatReleased(CANCELLED) → Payment → CANCELLED 전이 (Phase D)
 * - SeatReleased(PAYMENT_FAILED), SeatReserved → 추가 액션 없음 (결제 측은 이미 상태 전이 완료)
 */
@Component
@KafkaListener(topics = [KafkaConfig.TOPIC_SEAT_EVENTS])
class SeatEventConsumer(
    private val paymentService: PaymentService,
    private val terminationService: PaymentTerminationService,
) {
    private val logger = LoggerFactory.getLogger(SeatEventConsumer::class.java)

    @KafkaHandler
    fun onSeatHeld(event: SeatHeld) {
        logger.info("SeatHeld received: seatId={}, userId={}, amount={}", event.seatId, event.userId, event.amount)
        paymentService.createPendingForSeat(
            seatId = event.seatId,
            userId = event.userId,
            eventId = event.eventId,
            amount = event.amount
        )
    }

    @KafkaHandler
    fun onHoldExpired(event: HoldExpired) {
        logger.info("HoldExpired received: seatId={}", event.seatId)
        terminationService.expirePending(event.seatId)
    }

    @KafkaHandler
    fun onSeatReleased(event: SeatReleased) {
        if (event.reason == SeatReleaseReason.CANCELLED) {
            logger.info("SeatReleased(CANCELLED) received: seatId={}", event.seatId)
            terminationService.cancelPending(event.seatId)
        }
        // PAYMENT_FAILED 경로는 payment-service가 이미 FAILED로 전이해두었으므로 추가 처리 없음
    }

    @KafkaHandler
    fun onSeatReserved(event: SeatReserved) { /* 추가 액션 없음 */ }
}

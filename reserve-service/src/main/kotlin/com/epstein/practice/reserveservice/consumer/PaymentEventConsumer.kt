package com.epstein.practice.reserveservice.consumer

import com.epstein.practice.common.event.PaymentCancelled
import com.epstein.practice.common.event.PaymentExpired
import com.epstein.practice.common.event.PaymentFailed
import com.epstein.practice.common.event.PaymentRequested
import com.epstein.practice.common.event.PaymentSucceeded
import com.epstein.practice.common.event.SeatReleaseReason
import com.epstein.practice.common.event.SeatReleased
import com.epstein.practice.reserveservice.main.cache.EventCacheRepository
import com.epstein.practice.reserveservice.config.KafkaConfig
import com.epstein.practice.reserveservice.type.entity.SeatStatus
import com.epstein.practice.reserveservice.main.repository.SeatRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaHandler
import org.springframework.kafka.annotation.KafkaListener
import com.epstein.practice.common.outbox.OutboxService
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * `payment.events` 토픽 consumer (reserve-service 측).
 *
 * - PaymentSucceeded → 좌석 RESERVED 확정 + SeatReserved 발행
 * - PaymentFailed → Saga 보상: 좌석 AVAILABLE 복구 + SeatReleased(PAYMENT_FAILED) 발행
 * - 그 외(PaymentRequested, PaymentExpired, PaymentCancelled) → 관심 없음, 기본 핸들러로 무시
 *
 * @KafkaListener + @KafkaHandler 조합으로 타입 기반 라우팅.
 */
@Component
@KafkaListener(topics = [KafkaConfig.TOPIC_PAYMENT_EVENTS])
class PaymentEventConsumer(
    private val seatRepository: SeatRepository,
    private val eventCache: EventCacheRepository,
    private val outboxService: OutboxService,
) {
    private val logger = LoggerFactory.getLogger(PaymentEventConsumer::class.java)

    @KafkaHandler
    @Transactional
    fun onSucceeded(event: PaymentSucceeded) {
        val seat = seatRepository.findById(event.seatId).orElse(null) ?: run {
            logger.warn("PaymentSucceeded for unknown seat {}", event.seatId)
            return
        }
        if (seat.status != SeatStatus.PAYMENT_PENDING) {
            logger.info("Seat {} already in state {}, skipping PaymentSucceeded", seat.id, seat.status)
            return
        }
        seat.status = SeatStatus.RESERVED
        seat.reservedAt = LocalDateTime.now()
        logger.info("Seat RESERVED via PaymentSucceeded: seat={}, user={}, paymentId={}",
            seat.id, event.userId, event.paymentId)
    }

    @KafkaHandler
    @Transactional
    fun onFailed(event: PaymentFailed) {
        val seat = seatRepository.findById(event.seatId).orElse(null) ?: run {
            logger.warn("PaymentFailed for unknown seat {}", event.seatId)
            return
        }
        if (seat.status != SeatStatus.PAYMENT_PENDING) {
            logger.info("Seat {} already in state {}, skipping PaymentFailed", seat.id, seat.status)
            return
        }
        val section = seat.section
        val eventId = seat.eventId
        val userId = seat.userId ?: event.userId
        seat.status = SeatStatus.AVAILABLE
        seat.userId = null
        seat.reservedAt = null
        eventCache.adjustSeatCounts(eventId, 1, section)
        eventCache.markSeatAvailable(eventId, seat.id)

        outboxService.save(
            KafkaConfig.TOPIC_SEAT_EVENTS,
            seat.id.toString(),
            SeatReleased(
                seatId = seat.id,
                userId = userId,
                eventId = eventId,
                reason = SeatReleaseReason.PAYMENT_FAILED
            )
        )
        logger.info("Seat AVAILABLE via PaymentFailed: seat={}, reason={}", seat.id, event.reason)
    }

    @KafkaHandler
    fun onRequested(event: PaymentRequested) {
        // reserve-service는 PaymentRequested 발행자라 자기 자신은 소비 안 함. 무시
    }

    @KafkaHandler
    fun onExpired(event: PaymentExpired) {
        // payment EXPIRED는 reserve-service에선 추가 액션 없음 (seat은 HoldExpired로 이미 복구됨)
    }

    @KafkaHandler
    fun onCancelled(event: PaymentCancelled) {
        // payment CANCELLED는 reserve-service에선 추가 액션 없음 (seat은 SeatReleased(CANCELLED)로 이미 복구됨)
    }
}

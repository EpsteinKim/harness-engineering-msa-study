package com.epstein.practice.reserveservice.consumer

import com.epstein.practice.common.event.HoldExpired
import com.epstein.practice.common.event.SeatHeld
import com.epstein.practice.common.event.SeatReleased
import com.epstein.practice.common.event.SeatReserved
import com.epstein.practice.reserveservice.cache.EventCacheRepository
import com.epstein.practice.reserveservice.config.KafkaConfig
import com.epstein.practice.reserveservice.entity.SeatStatus
import com.epstein.practice.reserveservice.repository.SeatRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * `seat.events` 토픽 consumer (reserve-service 측 self-consume).
 *
 * - HoldExpired → 좌석 AVAILABLE 복구 + 잔여석 카운트 증가
 * - 다른 이벤트(SeatHeld/Reserved/Released)는 자기 발행분이라 skip
 */
@Component
@KafkaListener(topics = [KafkaConfig.TOPIC_SEAT_EVENTS])
class SeatEventConsumer(
    private val seatRepository: SeatRepository,
    private val eventCache: EventCacheRepository,
) {
    private val logger = LoggerFactory.getLogger(SeatEventConsumer::class.java)

    @KafkaHandler
    @Transactional
    fun onHoldExpired(event: HoldExpired) {
        val seat = seatRepository.findById(event.seatId).orElse(null) ?: run {
            logger.warn("HoldExpired for unknown seat {}", event.seatId)
            return
        }
        if (seat.status != SeatStatus.PAYMENT_PENDING) {
            logger.info("Seat {} already in state {}, skipping HoldExpired", seat.id, seat.status)
            return
        }
        val section = seat.section
        val eventId = seat.eventId
        seat.status = SeatStatus.AVAILABLE
        seat.userId = null
        seat.reservedAt = null
        eventCache.adjustSeatCounts(eventId, 1, section)
        eventCache.markSeatAvailable(eventId, seat.id)
        logger.info("Seat AVAILABLE via HoldExpired: seat={}, event={}", seat.id, eventId)
    }

    @KafkaHandler
    fun onHeld(event: SeatHeld) { /* self-published, ignore */ }

    @KafkaHandler
    fun onReserved(event: SeatReserved) { /* reserved에 대한 추가 로직 없음 */ }

    @KafkaHandler
    fun onReleased(event: SeatReleased) { /* self-published, ignore */ }
}

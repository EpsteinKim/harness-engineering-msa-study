package com.epstein.practice.reserveservice.consumer

import com.epstein.practice.common.event.SeatHeld
import com.epstein.practice.reserveservice.cache.EventCacheRepository
import com.epstein.practice.reserveservice.cache.QueueCacheRepository
import com.epstein.practice.reserveservice.event.EnqueueMessage
import com.epstein.practice.reserveservice.config.KafkaConfig
import com.epstein.practice.reserveservice.config.ReserveConfig
import com.epstein.practice.reserveservice.service.ReservationService
import com.epstein.practice.reserveservice.service.SeatService
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Component

/**
 * `reserve.queue` 토픽 consumer.
 * 기존 `DynamicScheduler.processEvent` 단건 처리 로직을 이식.
 *
 * - 파티션 키: `eventId:userId%K` (ReservationService.enqueue에서 발행)
 * - 같은 파티션은 동일 consumer가 순차 처리 → 이벤트 내 FIFO 유지
 * - 실패·낙관적 락 충돌 시 기존 동작 그대로 (유저 드롭)
 * - 좌석 배정 성공 시 `seat.events`로 `SeatHeld` 발행 (payment-service가 Payment(PENDING) 생성)
 */
@Component
class QueueConsumer(
    private val reserveService: ReservationService,
    private val seatService: SeatService,
    private val eventCache: EventCacheRepository,
    private val queueCache: QueueCacheRepository,
    private val kafkaTemplate: KafkaTemplate<String, Any>,
) {
    private val logger = LoggerFactory.getLogger(QueueConsumer::class.java)

    @KafkaListener(topics = [KafkaConfig.TOPIC_QUEUE])
    fun onMessage(message: EnqueueMessage) {
        val eventId = message.eventId
        val userId = message.userId

        val remaining = eventCache.getRemainingSeats(eventId)
        if (remaining <= 0) {
            logger.info("No remaining seats for event {}, dropping user={}", eventId, userId)
            reserveService.removeFromWaiting(eventId, userId)
            return
        }

        if (!queueCache.isInQueue(eventId, userId)) {
            logger.info("User {} already removed from queue, skipping", userId)
            reserveService.removeFromWaiting(eventId, userId)
            return
        }

        val userIdLong = userId.toLongOrNull()
        if (userIdLong == null) {
            logger.warn("Invalid userId format: {}", userId)
            reserveService.removeFromWaiting(eventId, userId)
            return
        }

        try {
            val result = if (message.section != null) {
                seatService.reserveBySection(eventId, message.section, userIdLong)
            } else if (message.seatId != null) {
                seatService.reserveBySeatId(eventId, message.seatId, userIdLong)
            } else {
                logger.warn("Invalid request data for user {}", userId)
                reserveService.removeFromWaiting(eventId, userId)
                return
            }

            reserveService.removeFromWaiting(eventId, userId)
            if (result.success) {
                eventCache.adjustSeatCounts(eventId, -1, result.section)
                if (eventCache.getSeatSelectionType(eventId) == "SEAT_PICK") {
                    eventCache.markSeatReserved(eventId, result.seatId)
                }
                publishSeatHeld(eventId, userIdLong, result.seatId, result.section)
                logger.info("Reservation succeeded: user={}, event={}, seat={}", userId, eventId, result.seatId)
            } else {
                if (message.seatId != null) {
                    eventCache.releaseHold(eventId, message.seatId, userId)
                }
                logger.info("Reservation failed: user={}, reason={}", userId, result.message)
            }
        } catch (e: ObjectOptimisticLockingFailureException) {
            logger.info("Optimistic lock conflict: user={}, event={}, seat={}", userId, eventId, message.seatId)
            if (message.seatId != null) {
                eventCache.releaseHold(eventId, message.seatId, userId)
            }
            reserveService.removeFromWaiting(eventId, userId)
        } catch (e: Exception) {
            logger.error("Unexpected error processing user {} for event {}", userId, eventId, e)
            reserveService.removeFromWaiting(eventId, userId)
        }
    }

    private fun publishSeatHeld(eventId: Long, userId: Long, seatId: Long, section: String?) {
        val amount = eventCache.getSeatPrice(eventId, seatId)
        val event = SeatHeld(
            seatId = seatId,
            userId = userId,
            eventId = eventId,
            section = section ?: "",
            amount = amount,
            heldUntilMs = System.currentTimeMillis() + ReserveConfig.HOLD_TTL_MS
        )
        kafkaTemplate.send(KafkaConfig.TOPIC_SEAT_EVENTS, seatId.toString(), event)
    }
}

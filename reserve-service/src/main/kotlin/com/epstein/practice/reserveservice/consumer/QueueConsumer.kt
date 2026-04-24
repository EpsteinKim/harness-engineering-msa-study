package com.epstein.practice.reserveservice.consumer

import com.epstein.practice.reserveservice.main.cache.EventCacheRepository
import com.epstein.practice.reserveservice.main.cache.QueueCacheRepository
import com.epstein.practice.reserveservice.type.event.EnqueueMessage
import com.epstein.practice.reserveservice.config.KafkaConfig
import com.epstein.practice.reserveservice.main.service.ReservationService
import com.epstein.practice.reserveservice.main.service.SagaOrchestrator
import com.epstein.practice.reserveservice.main.service.SeatService
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Component

/**
 * `reserve.queue` 토픽 consumer.
 *
 * - 파티션 키: `eventId:userId%K` (ReservationService.enqueue에서 발행)
 * - 좌석 배정 성공 시 Saga 시작 → CreatePaymentCommand 발행
 */
@Component
class QueueConsumer(
    private val reserveService: ReservationService,
    private val seatService: SeatService,
    private val eventCache: EventCacheRepository,
    private val queueCache: QueueCacheRepository,
    private val sagaOrchestrator: SagaOrchestrator,
) {
    private val logger = LoggerFactory.getLogger(QueueConsumer::class.java)

    @KafkaListener(topics = [KafkaConfig.TOPIC_RESERVE_QUEUE])
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
                val amount = eventCache.getSeatPrice(eventId, result.seatId)
                sagaOrchestrator.startSaga(eventId, userIdLong, result.seatId, amount)
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
}

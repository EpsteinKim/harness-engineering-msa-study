package com.epstein.practice.reserveservice.scheduler

import com.epstein.practice.reserveservice.service.ReservationQueueService
import com.epstein.practice.reserveservice.service.SeatReservationService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class ReservationScheduler(
    private val queueService: ReservationQueueService,
    private val seatReservationService: SeatReservationService,
    @Value("\${queue.throttle.rate:10}") private val throttleRate: Long
) {
    private val logger = LoggerFactory.getLogger(ReservationScheduler::class.java)

    @Scheduled(fixedRate = 1000)
    fun processQueue() {
        val dequeued = queueService.dequeue(throttleRate)
        if (dequeued.isEmpty()) return

        logger.info("Dequeued {} reservation requests", dequeued.size)

        for (userId in dequeued) {
            val data = queueService.getRequestData(userId)
            if (data == null) {
                logger.warn("No metadata for user {}", userId)
                queueService.fail(userId)
                continue
            }

            val result = if (data.section != null) {
                seatReservationService.reserveSeatBySection(data.eventId, data.section, userId)
            } else if (data.seatId != null) {
                seatReservationService.reserveSeat(data.eventId, data.seatId, userId)
            } else {
                logger.warn("Invalid request data for user {}", userId)
                queueService.fail(userId)
                continue
            }

            if (result.success) {
                queueService.complete(userId)
                logger.info("Reservation succeeded: user={}, event={}, seat={}", userId, data.eventId, result.seatId)
            } else {
                queueService.fail(userId)
                logger.info("Reservation failed: user={}, reason={}", userId, result.message)
            }
        }
    }

    @Scheduled(fixedRate = 30000)
    fun reEnqueueExpired() {
        val count = queueService.reEnqueueExpired()
        if (count > 0) {
            logger.info("Re-enqueued {} expired reservation requests", count)
        }
    }
}

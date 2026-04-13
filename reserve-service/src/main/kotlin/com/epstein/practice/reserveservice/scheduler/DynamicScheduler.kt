package com.epstein.practice.reserveservice.scheduler

import com.epstein.practice.reserveservice.cache.EventCacheRepository
import com.epstein.practice.reserveservice.cache.QueueCacheRepository
import com.epstein.practice.reserveservice.service.ReservationService
import com.epstein.practice.reserveservice.service.SeatService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture

@Component
class DynamicScheduler(
    private val taskScheduler: TaskScheduler,
    private val reserveService: ReservationService,
    private val seatService: SeatService,
    private val eventCache: EventCacheRepository,
    private val queueCache: QueueCacheRepository,
    @Value("\${queue.throttle.rate:10}") private val throttleRate: Long
) {
    private val logger = LoggerFactory.getLogger(DynamicScheduler::class.java)
    private val activeTasks = ConcurrentHashMap<Long, ScheduledFuture<*>>()

    fun startProcessing(eventId: Long) {
        if (activeTasks.containsKey(eventId)) return
        val future = taskScheduler.scheduleAtFixedRate(
            { processEvent(eventId) },
            Duration.ofSeconds(1)
        )
        activeTasks[eventId] = future
        logger.info("Started queue processing for event {}", eventId)
    }

    fun stopProcessing(eventId: Long) {
        activeTasks.remove(eventId)?.cancel(false)
        logger.info("Stopped queue processing for event {}", eventId)
    }

    fun isProcessing(eventId: Long): Boolean = activeTasks.containsKey(eventId)

    private fun processEvent(eventId: Long) {
        val users = reserveService.peekWaiting(eventId, throttleRate)
        if (users.isEmpty()) return

        logger.info("Processing {} reservation requests for event {}", users.size, eventId)

        for (userId in users) {
            val remaining = eventCache.getRemainingSeats(eventId)
            if (remaining <= 0) {
                logger.info("No remaining seats for event {}, clearing waiting queue and stopping scheduler", eventId)
                clearWaitingQueue(eventId)
                stopProcessing(eventId)
                return
            }

            if (!queueCache.isInQueue(eventId, userId)) {
                logger.info("User {} already removed from queue, cleaning up metadata", userId)
                reserveService.removeFromWaiting(eventId, userId)
                continue
            }

            val data = reserveService.getRequestData(eventId, userId)
            if (data == null) {
                logger.warn("No metadata for user {}", userId)
                reserveService.removeFromWaiting(eventId, userId)
                continue
            }

            val userIdLong = userId.toLongOrNull()
            if (userIdLong == null) {
                logger.warn("Invalid userId format: {}", userId)
                reserveService.removeFromWaiting(eventId, userId)
                continue
            }

            try {
                val result = if (data.section != null) {
                    seatService.reserveBySection(data.eventId, data.section, userIdLong)
                } else if (data.seatId != null) {
                    seatService.reserveBySeatId(data.eventId, data.seatId, userIdLong)
                } else {
                    logger.warn("Invalid request data for user {}", userId)
                    reserveService.removeFromWaiting(eventId, userId)
                    continue
                }

                reserveService.removeFromWaiting(eventId, userId)
                if (result.success) {
                    eventCache.adjustSeatCounts(eventId, -1, result.section)

                    if (eventCache.getSeatSelectionType(eventId) == "SEAT_PICK") {
                        eventCache.markSeatReserved(eventId, result.seatId)
                    }

                    logger.info("Reservation succeeded: user={}, event={}, seat={}", userId, data.eventId, result.seatId)
                } else {
                    if (data.seatId != null) {
                        eventCache.releaseHold(eventId, data.seatId, userId)
                    }
                    logger.info("Reservation failed: user={}, reason={}", userId, result.message)
                }
            } catch (e: ObjectOptimisticLockingFailureException) {
                logger.info("Optimistic lock conflict: user={}, event={}, seat={}", userId, eventId, data.seatId)
                if (data.seatId != null) {
                    eventCache.releaseHold(eventId, data.seatId, userId)
                }
                reserveService.removeFromWaiting(eventId, userId)
            } catch (e: Exception) {
                logger.error("Unexpected error processing user {} for event {}", userId, eventId, e)
                reserveService.removeFromWaiting(eventId, userId)
            }
        }
    }

    private fun clearWaitingQueue(eventId: Long) {
        val allUsers = queueCache.peekQueue(eventId, Long.MAX_VALUE)
        allUsers.forEach { reserveService.removeFromWaiting(eventId, it) }
    }
}

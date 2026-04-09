package com.epstein.practice.reserveservice.scheduler

import com.epstein.practice.reserveservice.constant.eventCacheKey
import com.epstein.practice.reserveservice.constant.seatCacheKey
import com.epstein.practice.reserveservice.constant.sectionAvailableField
import com.epstein.practice.reserveservice.constant.waitingKey
import com.epstein.practice.reserveservice.service.ReservationService
import com.epstein.practice.reserveservice.service.SeatService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
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
    private val redis: StringRedisTemplate,
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
            val remaining = redis.opsForHash<String, String>()
                .get(eventCacheKey(eventId), "remainingSeats")?.toLongOrNull() ?: 0
            if (remaining <= 0) {
                logger.info("No remaining seats for event {}, stopping processing", eventId)
                break
            }

            val stillInQueue = redis.opsForZSet().score(waitingKey(eventId), userId) != null
            if (!stillInQueue) {
                logger.info("User {} already removed from queue, skipping", userId)
                continue
            }

            val data = reserveService.getRequestData(userId)
            if (data == null) {
                logger.warn("No metadata for user {}", userId)
                reserveService.removeFromWaiting(eventId, userId)
                continue
            }

            val result = if (data.section != null) {
                seatService.reserveBySection(data.eventId, data.section, userId)
            } else if (data.seatId != null) {
                seatService.reserveBySeatId(data.eventId, data.seatId, userId)
            } else {
                logger.warn("Invalid request data for user {}", userId)
                reserveService.removeFromWaiting(eventId, userId)
                continue
            }

            reserveService.removeFromWaiting(eventId, userId)
            if (result.success) {
                val hashOps = redis.opsForHash<String, String>()
                hashOps.increment(eventCacheKey(eventId), "remainingSeats", -1)
                result.section?.let { section ->
                    hashOps.increment(eventCacheKey(eventId), sectionAvailableField(section), -1)
                }

                val selectionType = hashOps.get(eventCacheKey(eventId), "seatSelectionType")
                if (selectionType == "SEAT_PICK") {
                    val currentValue = hashOps.get(seatCacheKey(eventId), result.seatId.toString())
                    if (currentValue != null) {
                        val parts = currentValue.split(":")
                        if (parts.size >= 3) {
                            hashOps.put(seatCacheKey(eventId), result.seatId.toString(), "${parts[0]}:${parts[1]}:RESERVED")
                        }
                    }
                }

                logger.info("Reservation succeeded: user={}, event={}, seat={}", userId, data.eventId, result.seatId)
            } else {
                logger.info("Reservation failed: user={}, reason={}", userId, result.message)
            }
        }
    }
}

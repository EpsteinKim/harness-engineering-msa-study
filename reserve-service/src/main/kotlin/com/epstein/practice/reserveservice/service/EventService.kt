package com.epstein.practice.reserveservice.service

import com.epstein.practice.reserveservice.constant.eventCacheKey
import com.epstein.practice.reserveservice.constant.seatCacheKey
import com.epstein.practice.reserveservice.constant.sectionAvailableField
import com.epstein.practice.reserveservice.constant.sectionTotalField
import com.epstein.practice.reserveservice.entity.Event
import com.epstein.practice.reserveservice.entity.EventStatus
import com.epstein.practice.reserveservice.entity.SeatSelectionType
import com.epstein.practice.reserveservice.repository.EventRepository
import com.epstein.practice.reserveservice.repository.SeatRepository
import com.epstein.practice.reserveservice.repository.support.SeatQueryRepository
import com.epstein.practice.reserveservice.scheduler.DynamicScheduler
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDateTime

@Service
class EventService(
    private val eventRepository: EventRepository,
    private val seatRepository: SeatRepository,
    private val seatQueryRepository: SeatQueryRepository,
    private val redis: StringRedisTemplate,
    private val dynamicScheduler: DynamicScheduler
) {
    private val logger = LoggerFactory.getLogger(EventService::class.java)

    @Transactional
    fun openEvents(): Int {
        val now = LocalDateTime.now()
        val events = eventRepository.findEventsToOpen(EventStatus.CLOSED, now)

        for (event in events) {
            event.status = EventStatus.OPEN
            eventRepository.save(event)
            redisCacheEvent(event)
            dynamicScheduler.startProcessing(event.id)
            logger.info("Opened event: id={}, name={}", event.id, event.name)
        }
        return events.size
    }

    @Transactional
    fun closeEvents(): Int {
        val now = LocalDateTime.now()
        val events = eventRepository.findEventsToClose(EventStatus.OPEN, now)

        for (event in events) {
            event.status = EventStatus.CLOSED
            eventRepository.save(event)
            redis.delete(eventCacheKey(event.id))
            redis.delete(seatCacheKey(event.id))
            dynamicScheduler.stopProcessing(event.id)
            logger.info("Closed event: id={}, name={}", event.id, event.name)
        }
        return events.size
    }

    fun getOpenEventIds(): List<Long> =
        eventRepository.findByStatus(EventStatus.OPEN).map { it.id }

    fun isEventOpen(eventId: Long): Boolean {
        return redis.hasKey(eventCacheKey(eventId)) == true
    }

    fun syncAllRemainingSeats(): Int {
        val openEvents = eventRepository.findByStatus(EventStatus.OPEN)
        val hashOps = redis.opsForHash<String, String>()
        for (event in openEvents) {
            dynamicScheduler.stopProcessing(event.id)
            try {
                val key = eventCacheKey(event.id)
                val actualCount = seatRepository.countAvailableSeats(event.id)
                hashOps.put(key, "remainingSeats", actualCount.toString())

                val sectionData = seatQueryRepository.countAvailableBySection(event.id)
                for (section in sectionData) {
                    hashOps.put(key, sectionAvailableField(section.section), section.availableCount.toString())
                }

                if (event.seatSelectionType == SeatSelectionType.SEAT_PICK) {
                    cacheAllSeats(event.id)
                }
            } finally {
                dynamicScheduler.startProcessing(event.id)
            }
        }
        return openEvents.size
    }

    private fun redisCacheEvent(event: Event) {
        val key = eventCacheKey(event.id)
        val remainingSeats = seatRepository.countAvailableSeats(event.id)
        val fields = mutableMapOf(
            "name" to event.name,
            "remainingSeats" to remainingSeats.toString(),
            "ticketCloseTime" to (event.ticketCloseTime?.toString() ?: ""),
            "eventTime" to event.eventTime.toString(),
            "seatSelectionType" to event.seatSelectionType.name
        )

        val sectionData = seatQueryRepository.countAvailableBySection(event.id)
        for (section in sectionData) {
            fields[sectionAvailableField(section.section)] = section.availableCount.toString()
            fields[sectionTotalField(section.section)] = section.totalCount.toString()
        }

        redis.opsForHash<String, String>().putAll(key, fields)

        val closeTime = event.ticketCloseTime ?: return
        val ttl = Duration.between(LocalDateTime.now(), closeTime)
        if (!ttl.isNegative) {
            redis.expire(key, ttl)
        }

        if (event.seatSelectionType == SeatSelectionType.SEAT_PICK) {
            cacheAllSeats(event.id)
            if (closeTime != null && !ttl.isNegative) {
                redis.expire(seatCacheKey(event.id), ttl)
            }
        }
    }

    private fun cacheAllSeats(eventId: Long) {
        val seats = seatRepository.findByEventId(eventId)
        if (seats.isEmpty()) return

        val seatFields = seats.associate { seat ->
            seat.id.toString() to "${seat.seatNumber}:${seat.section}:${seat.status.name}"
        }
        redis.opsForHash<String, String>().putAll(seatCacheKey(eventId), seatFields)
    }
}

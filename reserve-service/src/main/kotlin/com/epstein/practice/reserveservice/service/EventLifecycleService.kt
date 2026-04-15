package com.epstein.practice.reserveservice.service

import com.epstein.practice.reserveservice.cache.EventCacheRepository
import com.epstein.practice.reserveservice.constant.sectionAvailableField
import com.epstein.practice.reserveservice.constant.sectionPriceField
import com.epstein.practice.reserveservice.constant.sectionTotalField
import com.epstein.practice.reserveservice.entity.Event
import com.epstein.practice.reserveservice.entity.EventStatus
import com.epstein.practice.reserveservice.entity.SeatSelectionType
import com.epstein.practice.reserveservice.repository.EventRepository
import com.epstein.practice.reserveservice.repository.SeatRepository
import com.epstein.practice.reserveservice.repository.support.SeatQueryRepository
import com.epstein.practice.reserveservice.scheduler.DynamicScheduler
import com.fasterxml.jackson.databind.util.JSONPObject
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset

@Service
class EventLifecycleService(
    private val eventRepository: EventRepository,
    private val seatRepository: SeatRepository,
    private val seatQueryRepository: SeatQueryRepository,
    private val eventCache: EventCacheRepository,
    private val dynamicScheduler: DynamicScheduler
) {
    private val logger = LoggerFactory.getLogger(EventLifecycleService::class.java)

    @Transactional
    fun openEvents(): Int {
        val now = LocalDateTime.now()
        val events = eventRepository.findEventsToOpen(EventStatus.CLOSED, now)

        for (event in events) {
            event.status = EventStatus.OPEN
            eventRepository.save(event)
            cacheEvent(event)
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
            eventCache.removeOpenEventIndex(event.id)
            eventCache.deleteEvent(event.id)
            eventCache.deleteSeatCache(event.id)
            dynamicScheduler.stopProcessing(event.id)
            logger.info("Closed event: id={}, name={}", event.id, event.name)
        }
        return events.size
    }

    fun getOpenEventIds(): List<Long> =
        eventRepository.findByStatus(EventStatus.OPEN).map { it.id }

    fun warmupCache(): Int {
        val openEvents = eventRepository.findByStatus(EventStatus.OPEN)
        for (event in openEvents) {
            cacheEvent(event)
            dynamicScheduler.startProcessing(event.id)
            logger.info("Warmed up cache for event: id={}, name={}", event.id, event.name)
        }
        return openEvents.size
    }

    fun isEventOpen(eventId: Long): Boolean {
        return eventCache.exists(eventId)
    }

    fun syncAllRemainingSeats(): Int {
        val openEvents = eventRepository.findByStatus(EventStatus.OPEN)
        for (event in openEvents) {
            logger.info("Syncing seats for event: id={}, name={}", event.id, event.name)
            dynamicScheduler.stopProcessing(event.id)
            try {
                val actualCount = seatRepository.countAvailableSeats(event.id)
                eventCache.setField(event.id, "remainingSeats", actualCount.toString())

                val sectionData = seatQueryRepository.countAvailableBySection(event.id)
                for (section in sectionData) {
                    eventCache.setField(event.id, sectionAvailableField(section.section), section.availableCount.toString())
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

    private fun cacheEvent(event: Event) {
        val remainingSeats = seatRepository.countAvailableSeats(event.id)
        val fields = mutableMapOf(
            "id" to event.id.toString(),
            "name" to event.name,
            "remainingSeats" to remainingSeats.toString(),
            "status" to event.status.name,
            "ticketOpenTime" to (event.ticketOpenTime?.toString() ?: ""),
            "ticketCloseTime" to (event.ticketCloseTime?.toString() ?: ""),
            "eventTime" to event.eventTime.toString(),
            "seatSelectionType" to event.seatSelectionType.name
        )

        val sectionData = seatQueryRepository.countAvailableBySection(event.id)
        for (section in sectionData) {
            fields[sectionAvailableField(section.section)] = section.availableCount.toString()
            fields[sectionTotalField(section.section)] = section.totalCount.toString()
        }

        val sectionPrices = seatRepository.findSectionPrices(event.id)
        for (sp in sectionPrices) {
            fields[sectionPriceField(sp.section)] = sp.price.toString()
        }

        eventCache.saveEvent(event.id, fields)

        val closeTime = event.ticketCloseTime ?: return
        val ttl = Duration.between(LocalDateTime.now(), closeTime)
        if (!ttl.isNegative) {
            eventCache.expireEvent(event.id, ttl)
        }

        if (event.seatSelectionType == SeatSelectionType.SEAT_PICK) {
            cacheAllSeats(event.id)
            cacheSeatPrices(event.id)
            if (!ttl.isNegative) {
                eventCache.expireSeatCache(event.id, ttl)
            }
        }

        // OPEN 상태면 목록 인덱스(ZSET)에 등록/갱신
        if (event.status == EventStatus.OPEN) {
            val score = event.ticketOpenTime?.toEpochSecond(ZoneOffset.UTC)?.toDouble() ?: 0.0
            eventCache.addOpenEventIndex(event.id, score)
        } else {
            eventCache.removeOpenEventIndex(event.id)
        }
    }

    private fun cacheAllSeats(eventId: Long) {
        val seats = seatRepository.findByEventId(eventId)
        if (seats.isEmpty()) return

        val seatFields = seats.associate { seat ->
            seat.id.toString() to "${seat.section}:${seat.seatNumber}:${seat.status.name}"
        }
        eventCache.saveAllSeats(eventId, seatFields)
    }

    private fun cacheSeatPrices(eventId: Long) {
        val seats = seatRepository.findByEventId(eventId)
        if (seats.isEmpty()) return
        val priceMap = seats.associate { it.id to it.priceAmount }
        eventCache.setSeatPrices(eventId, priceMap)
    }
}

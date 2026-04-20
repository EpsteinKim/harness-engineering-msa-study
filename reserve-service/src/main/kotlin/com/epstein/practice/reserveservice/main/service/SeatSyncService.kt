package com.epstein.practice.reserveservice.main.service

import com.epstein.practice.common.cache.sectionAvailableField
import com.epstein.practice.common.cache.sectionTotalField
import com.epstein.practice.common.cache.sectionPriceField
import com.epstein.practice.reserveservice.main.cache.EventCacheRepository
import com.epstein.practice.reserveservice.main.repository.SeatRepository
import com.epstein.practice.reserveservice.main.repository.support.SeatQueryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class SeatSyncService(
    private val seatRepository: SeatRepository,
    private val seatQueryRepository: SeatQueryRepository,
    private val eventCache: EventCacheRepository,
) {
    private val logger = LoggerFactory.getLogger(SeatSyncService::class.java)

    fun syncAllRemainingSeats(): Int {
        val openEventIds = eventCache.getOpenEventIdsOrderedByTicketOpenTime()
        for (eventId in openEventIds) {
            val actualCount = seatRepository.countAvailableSeats(eventId)
            eventCache.setField(eventId, "remainingSeats", actualCount.toString())

            val sectionData = seatQueryRepository.countAvailableBySection(eventId)
            for (section in sectionData) {
                eventCache.setField(eventId, sectionAvailableField(section.section), section.availableCount.toString())
            }

            val seatSelectionType = eventCache.getSeatSelectionType(eventId)
            if (seatSelectionType == "SEAT_PICK") {
                cacheAllSeats(eventId)
                cacheSeatPrices(eventId)
            }
            logger.info("Synced seats for event: id={}", eventId)
        }
        return openEventIds.size
    }

    fun warmSeatData(eventId: Long, seatSelectionType: String) {
        val remainingSeats = seatRepository.countAvailableSeats(eventId)
        val fields = mutableMapOf("remainingSeats" to remainingSeats.toString())

        val sectionData = seatQueryRepository.countAvailableBySection(eventId)
        for (section in sectionData) {
            fields[sectionAvailableField(section.section)] = section.availableCount.toString()
            fields[sectionTotalField(section.section)] = section.totalCount.toString()
        }

        val sectionPrices = seatRepository.findSectionPrices(eventId)
        for (sp in sectionPrices) {
            fields[sectionPriceField(sp.section)] = sp.price.toString()
        }

        eventCache.saveEvent(eventId, fields)

        if (seatSelectionType == "SEAT_PICK") {
            cacheAllSeats(eventId)
            cacheSeatPrices(eventId)
        }
    }

    fun clearSeatData(eventId: Long) {
        eventCache.deleteSeatCache(eventId)
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

package com.epstein.practice.reserveservice.main.service

import com.epstein.practice.reserveservice.main.cache.EventCacheRepository
import com.epstein.practice.reserveservice.config.CacheConfig.Companion.SEAT_MAP_CACHE
import com.epstein.practice.reserveservice.type.constant.parseSeatValue
import com.epstein.practice.common.cache.sectionAvailableField
import com.epstein.practice.common.cache.sectionPriceField
import com.epstein.practice.common.cache.sectionTotalField
import com.epstein.practice.reserveservice.type.dto.SeatMapEntry
import com.epstein.practice.reserveservice.type.dto.SectionAvailabilityResponse
import com.epstein.practice.reserveservice.type.entity.SeatStatus
import com.epstein.practice.reserveservice.main.repository.SeatRepository
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class SeatService(
    private val seatRepository: SeatRepository,
    private val eventCache: EventCacheRepository
) {
    @Transactional
    fun reserveBySeatId(eventId: Long, seatId: Long, userId: Long): ReservationResult {
        val seat = seatRepository.findByEventIdAndId(eventId, seatId)
            ?: return ReservationResult(userId, eventId, seatId, false, "seat not found")

        if (seat.status != SeatStatus.AVAILABLE) {
            return ReservationResult(userId, eventId, seatId, false, "seat already reserved")
        }

        seat.status = SeatStatus.PAYMENT_PENDING
        seat.userId = userId
        seat.reservedAt = LocalDateTime.now()
        seatRepository.save(seat)

        return ReservationResult(userId, eventId, seatId, true, "reservation successful", seat.section)
    }

    @Transactional
    fun reserveBySection(eventId: Long, section: String, userId: Long): ReservationResult {
        val seat = seatRepository.findFirstAvailableSeatForUpdate(eventId, section)
            ?: return ReservationResult(userId, eventId, 0, false, "no available seat in section $section")

        seat.status = SeatStatus.PAYMENT_PENDING
        seat.userId = userId
        seat.reservedAt = LocalDateTime.now()
        seatRepository.save(seat)

        return ReservationResult(userId, eventId, seat.id, true, "seat ${seat.seatNumber} reserved", section)
    }

    @Transactional
    fun releaseSeat(eventId: Long, userId: Long): ReservationResult {
        val seat = seatRepository.findByEventIdAndUserId(eventId, userId)
            ?: return ReservationResult(userId, eventId, 0, false, "no reserved seat for user")

        seat.status = SeatStatus.AVAILABLE
        seat.userId = null
        seat.reservedAt = null
        seatRepository.save(seat)

        return ReservationResult(userId, eventId, seat.id, true, "seat released", seat.section)
    }

    @Cacheable(cacheNames = [SEAT_MAP_CACHE], key = "#eventId + ':' + (#section ?: 'ALL')")
    fun getSeatMap(eventId: Long, section: String? = null): List<SeatMapEntry> {
        val raw = eventCache.getAllSeatFields(eventId)
        if (raw.isEmpty()) return emptyList()
        val priceMap = eventCache.getAllSeatPrices(eventId)
        val now = System.currentTimeMillis()
        val entries = mutableListOf<SeatMapEntry>()
        for ((key, value) in raw) {
            val seatId = key.toLongOrNull() ?: continue
            val parsed = parseSeatValue(value) ?: continue
            if (section != null && parsed.section != section) continue
            entries.add(
                SeatMapEntry(
                    seatId = seatId,
                    section = parsed.section,
                    seatNumber = parsed.num,
                    status = parsed.effectiveStatus(now),
                    priceAmount = priceMap[seatId] ?: 0
                )
            )
        }
        return entries.sortedWith(compareBy({ it.section }, { it.seatNumber }))
    }

    fun getSectionAvailability(eventId: Long): List<SectionAvailabilityResponse> {
        val allFields = eventCache.getAllFields(eventId)
        if (allFields.isEmpty()) return emptyList()

        val sections = allFields.keys
            .filter { it.startsWith("section:") && it.endsWith(":available") }
            .map { it.removePrefix("section:").removeSuffix(":available") }

        return sections.map { section ->
            SectionAvailabilityResponse(
                section = section,
                availableCount = allFields[sectionAvailableField(section)]?.toLongOrNull() ?: 0,
                totalCount = allFields[sectionTotalField(section)]?.toLongOrNull() ?: 0,
                priceAmount = allFields[sectionPriceField(section)]?.toLongOrNull() ?: 0
            )
        }.sortedBy { it.section }
    }


}

data class ReservationResult(
    val userId: Long,
    val eventId: Long,
    val seatId: Long,
    val success: Boolean,
    val message: String,
    val section: String? = null
)

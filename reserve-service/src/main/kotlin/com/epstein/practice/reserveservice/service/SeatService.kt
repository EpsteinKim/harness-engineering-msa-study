package com.epstein.practice.reserveservice.service

import com.epstein.practice.reserveservice.cache.EventCacheRepository
import com.epstein.practice.reserveservice.constant.sectionAvailableField
import com.epstein.practice.reserveservice.constant.sectionTotalField
import com.epstein.practice.reserveservice.dto.SectionAvailabilityResponse
import com.epstein.practice.reserveservice.entity.SeatStatus
import com.epstein.practice.reserveservice.repository.SeatRepository
import org.slf4j.LoggerFactory
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class SeatService(
    private val seatRepository: SeatRepository,
    private val eventCache: EventCacheRepository
) {
    private val logger = LoggerFactory.getLogger(SeatService::class.java)

    @Transactional
    fun reserveBySeatId(eventId: Long, seatId: Long, userId: Long): ReservationResult {
        val seat = seatRepository.findByEventIdAndId(eventId, seatId)
            ?: return ReservationResult(userId, eventId, seatId, false, "Seat not found")

        if (seat.status != SeatStatus.AVAILABLE) {
            return ReservationResult(userId, eventId, seatId, false, "Seat already reserved")
        }

        seat.status = SeatStatus.RESERVED
        seat.userId = userId
        seat.reservedAt = LocalDateTime.now()

        return try {
            seatRepository.save(seat)
            ReservationResult(userId, eventId, seatId, true, "Reservation successful", seat.section)
        } catch (e: ObjectOptimisticLockingFailureException) {
            logger.warn("Optimistic lock conflict for seat {} in event {} by user {}", seatId, eventId, userId)
            ReservationResult(userId, eventId, seatId, false, "Seat was taken by another user")
        }
    }

    @Transactional
    fun releaseSeat(eventId: Long, userId: Long): ReservationResult {
        val seat = seatRepository.findByEventIdAndUserId(eventId, userId)
            ?: return ReservationResult(userId, eventId, 0, false, "No reserved seat found")

        seat.status = SeatStatus.AVAILABLE
        seat.userId = null
        seat.reservedAt = null
        seatRepository.save(seat)

        return ReservationResult(userId, eventId, seat.id, true, "Seat released", seat.section)
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
                totalCount = allFields[sectionTotalField(section)]?.toLongOrNull() ?: 0
            )
        }.sortedBy { it.section }
    }

    @Transactional
    fun reserveBySection(eventId: Long, section: String, userId: Long): ReservationResult {
        val seat = seatRepository.findFirstAvailableSeatForUpdate(eventId, section)
            ?: return ReservationResult(userId, eventId, 0, false, "No available seat in section $section")

        seat.status = SeatStatus.RESERVED
        seat.userId = userId
        seat.reservedAt = LocalDateTime.now()

        return try {
            seatRepository.save(seat)
            ReservationResult(userId, eventId, seat.id, true, "Seat ${seat.seatNumber} reserved successfully", section)
        } catch (e: ObjectOptimisticLockingFailureException) {
            logger.warn("Optimistic lock conflict for section {} in event {} by user {}", section, eventId, userId)
            ReservationResult(userId, eventId, 0, false, "Seat was taken by another user")
        }
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

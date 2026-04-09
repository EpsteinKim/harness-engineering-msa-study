package com.epstein.practice.reserveservice.service

import com.epstein.practice.reserveservice.entity.SeatStatus
import com.epstein.practice.reserveservice.repository.SeatRepository
import org.slf4j.LoggerFactory
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class SeatService(
    private val seatRepository: SeatRepository
) {
    private val logger = LoggerFactory.getLogger(SeatService::class.java)

    @Transactional
    fun reserveBySeatId(eventId: Long, seatId: Long, userId: String): ReservationResult {
        val seat = seatRepository.findByEventIdAndId(eventId, seatId)
            ?: return ReservationResult(userId, eventId, seatId, false, "Seat not found")

        if (seat.status != SeatStatus.AVAILABLE) {
            return ReservationResult(userId, eventId, seatId, false, "Seat already reserved")
        }

        seat.status = SeatStatus.RESERVED
        seat.reservedBy = userId
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
    fun reserveBySection(eventId: Long, section: String, userId: String): ReservationResult {
        val seat = seatRepository.findFirstAvailableSeatForUpdate(eventId, section)
            ?: return ReservationResult(userId, eventId, 0, false, "No available seat in section $section")

        seat.status = SeatStatus.RESERVED
        seat.reservedBy = userId
        seat.reservedAt = LocalDateTime.now()
        seatRepository.save(seat)

        return ReservationResult(userId, eventId, seat.id, true, "Seat ${seat.seatNumber} reserved successfully", section)
    }
}

data class ReservationResult(
    val userId: String,
    val eventId: Long,
    val seatId: Long,
    val success: Boolean,
    val message: String,
    val section: String? = null
)

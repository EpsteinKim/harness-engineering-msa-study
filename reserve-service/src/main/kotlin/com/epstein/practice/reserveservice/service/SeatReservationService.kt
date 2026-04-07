package com.epstein.practice.reserveservice.service

import com.epstein.practice.reserveservice.entity.SeatStatus
import com.epstein.practice.reserveservice.repository.SeatRepository
import org.slf4j.LoggerFactory
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class SeatReservationService(
    private val seatRepository: SeatRepository
) {
    private val logger = LoggerFactory.getLogger(SeatReservationService::class.java)

    @Transactional
    fun reserveSeat(eventId: Long, seatId: Long, userId: String): ReservationResult {
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
            ReservationResult(userId, eventId, seatId, true, "Reservation successful")
        } catch (e: ObjectOptimisticLockingFailureException) {
            logger.warn("Optimistic lock conflict for seat {} in event {} by user {}", seatId, eventId, userId)
            ReservationResult(userId, eventId, seatId, false, "Seat was taken by another user")
        }
    }
}

data class ReservationResult(
    val userId: String,
    val eventId: Long,
    val seatId: Long,
    val success: Boolean,
    val message: String
)

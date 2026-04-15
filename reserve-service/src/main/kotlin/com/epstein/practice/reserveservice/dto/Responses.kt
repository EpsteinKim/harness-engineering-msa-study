package com.epstein.practice.reserveservice.dto

import com.epstein.practice.reserveservice.entity.SeatStatus
import java.time.LocalDateTime

data class EnqueueResponse(
    val userId: String,
    val position: Long?
)

data class ReservationResponse(
    val userId: String,
    val eventId: Long,
    val seatId: Long,
    val success: Boolean,
    val message: String
)

data class SectionAvailabilityResponse(
    val section: String,
    val availableCount: Long,
    val totalCount: Long,
    val priceAmount: Long
)

data class SeatDTO(
    val id: Long,
    val seatNumber: String,
    val section: String,
    val status: SeatStatus
)

data class SeatMapEntry(
    val seatId: Long,
    val section: String,
    val seatNumber: String,
    val status: String,
    val priceAmount: Long
)

data class EventSummaryResponse(
    val id: Long,
    val name: String,
    val eventTime: LocalDateTime,
    val status: String,
    val ticketOpenTime: LocalDateTime?,
    val ticketCloseTime: LocalDateTime?,
    val seatSelectionType: String,
    val remainingSeats: Long
)

data class MyReservationItem(
    val eventId: Long,
    val eventName: String,
    val eventTime: LocalDateTime,
    val seatId: Long,
    val seatNumber: String,
    val section: String,
    val priceAmount: Long,
    val paymentId: Long?,
    val paymentStatus: String?,
    val reservedAt: LocalDateTime?
)

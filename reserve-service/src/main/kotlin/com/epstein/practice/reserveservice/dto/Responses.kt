package com.epstein.practice.reserveservice.dto

import com.epstein.practice.reserveservice.entity.SeatStatus

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
    val totalCount: Long
)

data class SeatDTO(
    val id: Long,
    val seatNumber: String,
    val section: String,
    val status: SeatStatus
)

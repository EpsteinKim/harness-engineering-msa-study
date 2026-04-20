package com.epstein.practice.reserveservice.type.dto

import java.time.LocalDateTime

data class EnqueueResponse(
    val userId: String,
    val position: Long?
)

data class QueuePositionResponse(
    val position: Long?,
    val inQueue: Boolean
)

data class SectionAvailabilityResponse(
    val section: String,
    val availableCount: Long,
    val totalCount: Long,
    val priceAmount: Long
)

data class SeatMapEntry(
    val seatId: Long,
    val section: String,
    val seatNumber: String,
    val status: String,
    val priceAmount: Long
)

data class MyReservationItem(
    val eventId: Long,
    val eventName: String,
    val eventTime: LocalDateTime,
    val seatId: Long,
    val seatNumber: String,
    val section: String,
    val seatStatus: String,
    val priceAmount: Long,
    val paymentId: Long?,
    val paymentStatus: String?,
    val reservedAt: LocalDateTime?
)

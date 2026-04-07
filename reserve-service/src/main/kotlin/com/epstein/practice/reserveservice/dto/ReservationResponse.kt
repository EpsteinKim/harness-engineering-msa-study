package com.epstein.practice.reserveservice.dto

data class ReservationResponse(
    val userId: String,
    val eventId: Long,
    val seatId: Long,
    val success: Boolean,
    val message: String
)

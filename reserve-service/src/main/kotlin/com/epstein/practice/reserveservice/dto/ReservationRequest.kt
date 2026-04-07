package com.epstein.practice.reserveservice.dto

data class ReservationRequest(
    val userId: String,
    val eventId: Long,
    val seatId: Long
)

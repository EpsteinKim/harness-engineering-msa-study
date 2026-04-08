package com.epstein.practice.reserveservice.dto

data class ReservationRequest(
    val userId: String,
    val eventId: Long,
    val seatId: Long? = null,
    val section: String? = null
)

data class SectionReservationRequest(
    val userId: String,
    val eventId: Long,
    val section: String
)

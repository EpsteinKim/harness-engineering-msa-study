package com.epstein.practice.reserveservice.dto

data class ReservationRequest(
    val userId: String,
    val eventId: Long,
    val seatId: Long? = null,
    val section: String? = null
)

data class PaymentRequest(
    val userId: Long,
    val eventId: Long,
    val amount: Long,
    val method: String
)

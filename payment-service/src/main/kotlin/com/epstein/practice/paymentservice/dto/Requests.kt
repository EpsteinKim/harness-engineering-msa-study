package com.epstein.practice.paymentservice.dto

data class PaymentRequest(
    val userId: Long,
    val seatId: Long,
    val eventId: Long,
    val amount: Long,
    val method: String
)

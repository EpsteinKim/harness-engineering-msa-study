package com.epstein.practice.paymentservice.type.dto

data class PaymentRequest(
    val userId: Long,
    val seatId: Long,
    val eventId: Long,
    val amount: Long,
    val method: String
)

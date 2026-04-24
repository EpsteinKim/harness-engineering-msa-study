package com.epstein.practice.common.event

data class CreatePaymentCommand(
    val sagaId: Long,
    val seatId: Long,
    val userId: Long,
    val eventId: Long,
    val amount: Long,
)

data class ProcessPaymentCommand(
    val sagaId: Long,
    val seatId: Long,
    val userId: Long,
    val method: String,
)

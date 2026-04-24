package com.epstein.practice.paymentservice.type.dto

import com.epstein.practice.paymentservice.type.entity.Payment
import java.time.ZonedDateTime
import java.time.ZoneId

data class PaymentResponse(
    val id: Long,
    val status: String,
    val seatId: Long,
    val userId: Long,
    val eventId: Long,
    val amount: Long,
    val method: String?,
    val createdAt: ZonedDateTime,
    val completedAt: ZonedDateTime?
) {
    companion object {
        fun from(p: Payment) = PaymentResponse(
            id = p.id,
            status = p.status.name,
            seatId = p.seatId,
            userId = p.userId,
            eventId = p.eventId,
            amount = p.amount,
            method = p.method,
            createdAt = p.createdAt,
            completedAt = p.completedAt
        )
    }
}

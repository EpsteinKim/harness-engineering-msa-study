package com.epstein.practice.paymentservice.dto

import com.epstein.practice.paymentservice.entity.Payment
import java.time.LocalDateTime

data class PaymentResponse(
    val id: Long,
    val status: String,
    val seatId: Long,
    val userId: Long,
    val eventId: Long,
    val amount: Long,
    val method: String,
    val createdAt: LocalDateTime,
    val completedAt: LocalDateTime?
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

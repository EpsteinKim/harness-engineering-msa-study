package com.epstein.practice.paymentservice.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(
    name = "payment",
    indexes = [
        Index(name = "idx_payment_user_status", columnList = "user_id,status"),
        Index(name = "idx_payment_seat_status", columnList = "seat_id,status")
    ]
)
class Payment(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "seat_id", nullable = false)
    val seatId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "event_id", nullable = false)
    val eventId: Long,

    @Column(nullable = false)
    val amount: Long,

    @Column(nullable = false, length = 20)
    val method: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: PaymentStatus = PaymentStatus.PENDING,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "completed_at")
    var completedAt: LocalDateTime? = null
)

enum class PaymentStatus {
    PENDING, SUCCEEDED, FAILED
}

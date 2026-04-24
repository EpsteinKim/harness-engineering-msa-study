package com.epstein.practice.reserveservice.type.entity

import jakarta.persistence.*
import java.time.ZonedDateTime
import java.time.ZoneId

@Entity
@Table(name = "seat")
class Seat(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "event_id", nullable = false)
    val eventId: Long,

    @Column(name = "seat_number", nullable = false, length = 20)
    val seatNumber: String,

    @Column(nullable = false, length = 1)
    val section: String = "A",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: SeatStatus = SeatStatus.AVAILABLE,

    @Column(name = "user_id")
    var userId: Long? = null,

    @Column(name = "reserved_at")
    var reservedAt: ZonedDateTime? = null,

    @Column(name = "price_amount", nullable = false)
    val priceAmount: Long = 0,

    @Version
    val version: Long = 0
)

enum class SeatStatus {
    AVAILABLE,
    PAYMENT_PENDING,
    RESERVED
}

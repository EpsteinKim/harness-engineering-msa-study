package com.epstein.practice.reserveservice.entity

import com.epstein.practice.reserveservice.dto.SeatDTO
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "seat")
class Seat(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    val event: Event,

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
    var reservedAt: LocalDateTime? = null,

    @Version
    val version: Long = 0
) {
    fun toDTO(): SeatDTO = SeatDTO(this.id, this.seatNumber, this.section, this.status)
}

enum class SeatStatus {
    AVAILABLE,
    PAYMENT_PENDING,
    RESERVED
}

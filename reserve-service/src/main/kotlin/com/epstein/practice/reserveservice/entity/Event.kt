package com.epstein.practice.reserveservice.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(
    name = "event",
    indexes = [
        Index(name = "idx_event_status", columnList = "status")
    ]
)
class Event(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val name: String,

    @Column(name = "event_time", nullable = false)
    val eventTime: LocalDateTime,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: EventStatus = EventStatus.CLOSED,

    @Column(name = "ticket_open_time")
    val ticketOpenTime: LocalDateTime? = null,

    @Column(name = "ticket_close_time")
    val ticketCloseTime: LocalDateTime? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "seat_selection_type", nullable = false, length = 20)
    val seatSelectionType: SeatSelectionType = SeatSelectionType.SECTION_SELECT,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
)

enum class EventStatus {
    OPEN, CLOSED, DELETED
}

enum class SeatSelectionType {
    SECTION_SELECT, SEAT_PICK
}

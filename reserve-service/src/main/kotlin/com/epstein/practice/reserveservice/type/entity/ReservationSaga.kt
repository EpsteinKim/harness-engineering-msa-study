package com.epstein.practice.reserveservice.type.entity

import com.epstein.practice.reserveservice.type.constant.SagaStatus
import com.epstein.practice.reserveservice.type.constant.SagaStep
import jakarta.persistence.*
import java.time.ZonedDateTime

@Entity
@Table(name = "reservation_saga")
class ReservationSaga(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val eventId: Long,

    @Column(nullable = false)
    val userId: Long,

    @Column(nullable = false)
    val seatId: Long,

    var paymentId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var step: SagaStep,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var status: SagaStatus,

    @Column(nullable = false)
    val createdAt: ZonedDateTime = ZonedDateTime.now(),

    @Column(nullable = false)
    var updatedAt: ZonedDateTime = ZonedDateTime.now(),
)

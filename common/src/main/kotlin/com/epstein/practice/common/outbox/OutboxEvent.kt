package com.epstein.practice.common.outbox

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "outbox")
class OutboxEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, length = 100)
    val topic: String,

    @Column(length = 100)
    val key: String? = null,

    @Column(nullable = false, columnDefinition = "TEXT")
    val payload: String,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

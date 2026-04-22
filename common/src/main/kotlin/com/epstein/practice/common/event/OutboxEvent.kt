package com.epstein.practice.common.event

import java.time.LocalDateTime

interface OutboxEvent {
    val id: Long
    val topic: String
    val key: String
    val payload: String
    val createdAt: LocalDateTime
}
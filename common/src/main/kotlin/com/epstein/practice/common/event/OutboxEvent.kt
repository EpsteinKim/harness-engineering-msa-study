package com.epstein.practice.common.event

import java.time.ZonedDateTime
import java.time.ZoneId

interface OutboxEvent {
    val id: Long
    val topic: String
    val key: String
    val payload: String
    val createdAt: ZonedDateTime
}
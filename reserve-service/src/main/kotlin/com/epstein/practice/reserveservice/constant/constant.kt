package com.epstein.practice.reserveservice.constant

enum class RedisStatus {
    WAITING, SUCCESS, FAILED,
}

fun waitingKey(eventId: Long) = "reservation:waiting:$eventId"
fun eventCacheKey(eventId: Long) = "event:$eventId"
fun metadataKey(userId: String) = "reservation:metadata:$userId"
fun sectionAvailableField(section: String) = "section:$section:available"
fun sectionTotalField(section: String) = "section:$section:total"
fun seatCacheKey(eventId: Long) = "event:$eventId:seats"
package com.epstein.practice.reserveservice.constant

enum class RedisStatus {
    WAITING, SUCCESS, FAILED,
}

object ErrorCode {
    const val EVENT_NOT_OPEN = "EVENT_NOT_OPEN"
    const val NO_REMAINING_SEATS = "NO_REMAINING_SEATS"
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val INVALID_SECTION = "INVALID_SECTION"
    const val SEAT_NOT_FOUND = "SEAT_NOT_FOUND"
    const val SEAT_ALREADY_RESERVED = "SEAT_ALREADY_RESERVED"
    const val ALREADY_IN_QUEUE = "ALREADY_IN_QUEUE"
    const val QUEUE_NOT_FOUND = "QUEUE_NOT_FOUND"
}


fun waitingKey(eventId: Long) = "reservation:waiting:$eventId"
fun eventCacheKey(eventId: Long) = "event:$eventId"
fun metadataKey(eventId: Long, userId: String) = "reservation:metadata:$eventId:$userId"
fun sectionAvailableField(section: String) = "section:$section:available"
fun sectionTotalField(section: String) = "section:$section:total"
fun seatCacheKey(eventId: Long) = "event:$eventId:seats"
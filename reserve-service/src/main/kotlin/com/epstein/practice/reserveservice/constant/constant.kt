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
    const val SEAT_UNAVAILABLE = "SEAT_UNAVAILABLE"
    const val SECTION_FULL = "SECTION_FULL"
    const val ALREADY_IN_QUEUE = "ALREADY_IN_QUEUE"
    const val QUEUE_NOT_FOUND = "QUEUE_NOT_FOUND"
    const val USER_NOT_FOUND = "USER_NOT_FOUND"
    const val PAYMENT_PENDING_NOT_FOUND = "PAYMENT_PENDING_NOT_FOUND"
    const val PAYMENT_FAILED = "PAYMENT_FAILED"
}

object SeatCacheStatus {
    const val AVAILABLE = "AVAILABLE"
    const val HELD = "HELD"
    const val RESERVED = "RESERVED"
}

data class ParsedSeat(
    val section: String,
    val num: String,
    val status: String,
    val holdUserId: String? = null,
    val heldUntilMs: Long? = null
) {
    fun effectiveStatus(nowMs: Long): String =
        if (status == SeatCacheStatus.HELD && (heldUntilMs ?: 0) < nowMs) SeatCacheStatus.AVAILABLE
        else status
}

fun parseSeatValue(raw: String): ParsedSeat? {
    val p = raw.split(":")
    if (p.size < 3) return null
    return when (p[2]) {
        SeatCacheStatus.HELD ->
            if (p.size >= 5) ParsedSeat(p[0], p[1], p[2], p[3], p[4].toLongOrNull()) else null
        else -> ParsedSeat(p[0], p[1], p[2])
    }
}

fun waitingKey(eventId: Long) = "reservation:waiting:$eventId"
fun eventCacheKey(eventId: Long) = "event:$eventId"
fun metadataKey(eventId: Long, userId: String) = "reservation:metadata:$eventId:$userId"
fun sectionAvailableField(section: String) = "section:$section:available"
fun sectionTotalField(section: String) = "section:$section:total"
fun seatCacheKey(eventId: Long) = "event:$eventId:seats"

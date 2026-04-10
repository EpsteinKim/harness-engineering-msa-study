package com.epstein.practice.reserveservice.cache

import com.epstein.practice.reserveservice.constant.eventCacheKey
import com.epstein.practice.reserveservice.constant.seatCacheKey
import com.epstein.practice.reserveservice.constant.sectionAvailableField
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository
import java.time.Duration

@Repository
class EventCacheRepository(
    private val redis: StringRedisTemplate
) {
    private val hashOps = redis.opsForHash<String, String>()

    // === Event Cache ===

    fun saveEvent(eventId: Long, fields: Map<String, String>) {
        hashOps.putAll(eventCacheKey(eventId), fields)
    }

    fun deleteEvent(eventId: Long) {
        redis.delete(eventCacheKey(eventId))
    }

    fun expireEvent(eventId: Long, ttl: Duration) {
        redis.expire(eventCacheKey(eventId), ttl)
    }

    fun exists(eventId: Long): Boolean {
        return redis.hasKey(eventCacheKey(eventId)) == true
    }

    fun getRemainingSeats(eventId: Long): Long {
        return hashOps.get(eventCacheKey(eventId), "remainingSeats")?.toLongOrNull() ?: 0
    }

    fun getSeatSelectionType(eventId: Long): String {
        return hashOps.get(eventCacheKey(eventId), "seatSelectionType") ?: "SECTION_SELECT"
    }

    fun getAllFields(eventId: Long): Map<String, String> {
        return hashOps.entries(eventCacheKey(eventId))
    }

    fun setField(eventId: Long, field: String, value: String) {
        hashOps.put(eventCacheKey(eventId), field, value)
    }

    fun adjustSeatCounts(eventId: Long, delta: Long, section: String? = null) {
        hashOps.increment(eventCacheKey(eventId), "remainingSeats", delta)
        section?.let { hashOps.increment(eventCacheKey(eventId), sectionAvailableField(it), delta) }
    }

    // === Seat Cache ===

    fun saveAllSeats(eventId: Long, seatFields: Map<String, String>) {
        hashOps.putAll(seatCacheKey(eventId), seatFields)
    }

    fun deleteSeatCache(eventId: Long) {
        redis.delete(seatCacheKey(eventId))
    }

    fun expireSeatCache(eventId: Long, ttl: Duration) {
        redis.expire(seatCacheKey(eventId), ttl)
    }

    fun getSeatStatus(eventId: Long, seatId: Long): String? {
        return hashOps.get(seatCacheKey(eventId), seatId.toString())
    }

    fun markSeatReserved(eventId: Long, seatId: Long) {
        val current = getSeatStatus(eventId, seatId) ?: return
        val parts = current.split(":")
        if (parts.size >= 3) {
            hashOps.put(seatCacheKey(eventId), seatId.toString(), "${parts[0]}:${parts[1]}:RESERVED")
        }
    }
}

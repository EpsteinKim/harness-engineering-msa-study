package com.epstein.practice.coreservice.cache

import com.epstein.practice.common.cache.OPEN_EVENTS_INDEX_KEY
import com.epstein.practice.common.cache.eventCacheKey
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository
import java.time.Duration

@Repository
class EventCacheRepository(
    private val redis: StringRedisTemplate
) {
    private val hashOps = redis.opsForHash<String, String>()

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

    fun getAllFields(eventId: Long): Map<String, String> {
        return hashOps.entries(eventCacheKey(eventId))
    }

    fun addOpenEventIndex(eventId: Long, score: Double) {
        redis.opsForZSet().add(OPEN_EVENTS_INDEX_KEY, eventId.toString(), score)
    }

    fun removeOpenEventIndex(eventId: Long) {
        redis.opsForZSet().remove(OPEN_EVENTS_INDEX_KEY, eventId.toString())
    }

    fun getOpenEventIdsOrderedByTicketOpenTime(): List<Long> {
        return redis.opsForZSet().range(OPEN_EVENTS_INDEX_KEY, 0, -1)
            ?.mapNotNull { it.toLongOrNull() }
            ?: emptyList()
    }
}

package com.epstein.practice.reserveservice.main.cache

import com.epstein.practice.common.cache.OPEN_EVENTS_INDEX_KEY
import com.epstein.practice.common.cache.SEAT_PRICE_FIELD_PREFIX
import com.epstein.practice.common.cache.eventCacheKey
import com.epstein.practice.common.cache.seatCacheKey
import com.epstein.practice.common.cache.seatPriceField
import com.epstein.practice.common.cache.sectionAvailableField
import com.epstein.practice.common.cache.sectionPriceField
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Repository

@Repository
class EventCacheRepository(
    private val redis: StringRedisTemplate
) {
    private val hashOps = redis.opsForHash<String, String>()

    private val tryHoldScript: DefaultRedisScript<Long> = DefaultRedisScript<Long>().apply {
        setLocation(ClassPathResource("redis/try_hold_seat.lua"))
        resultType = Long::class.java
    }

    private val releaseHoldScript: DefaultRedisScript<Long> = DefaultRedisScript<Long>().apply {
        setLocation(ClassPathResource("redis/release_hold.lua"))
        resultType = Long::class.java
    }

    // === Event Cache ===

    fun saveEvent(eventId: Long, fields: Map<String, String>) {
        hashOps.putAll(eventCacheKey(eventId), fields)
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

    fun getSectionAvailable(eventId: Long, section: String): Long {
        return hashOps.get(eventCacheKey(eventId), sectionAvailableField(section))?.toLongOrNull() ?: 0
    }

    fun getSectionPrice(eventId: Long, section: String): Long {
        return hashOps.get(eventCacheKey(eventId), sectionPriceField(section))?.toLongOrNull() ?: 0
    }

    fun setSeatPrices(eventId: Long, prices: Map<Long, Long>) {
        if (prices.isEmpty()) return
        val fields = prices.mapKeys { (id, _) -> seatPriceField(id) }
            .mapValues { (_, v) -> v.toString() }
        hashOps.putAll(eventCacheKey(eventId), fields)
    }

    fun getSeatPrice(eventId: Long, seatId: Long): Long {
        return hashOps.get(eventCacheKey(eventId), seatPriceField(seatId))?.toLongOrNull() ?: 0
    }

    fun getAllSeatPrices(eventId: Long): Map<Long, Long> {
        val all = hashOps.entries(eventCacheKey(eventId))
        val prefix = seatPriceField(0L).substringBefore(":") + ":"
        return all.entries.asSequence()
            .filter { it.key.startsWith(prefix) }
            .mapNotNull { entry ->
                val seatId = entry.key.removePrefix(prefix).toLongOrNull() ?: return@mapNotNull null
                val price = entry.value.toLongOrNull() ?: return@mapNotNull null
                seatId to price
            }
            .toMap()
    }

    // === Open Events Index (ZSET, read-only — core-service가 관리) ===

    fun getOpenEventIdsOrderedByTicketOpenTime(): List<Long> {
        return redis.opsForZSet().range(OPEN_EVENTS_INDEX_KEY, 0, -1)
            ?.mapNotNull { it.toLongOrNull() }
            ?: emptyList()
    }

    // === Seat Cache ===

    fun saveAllSeats(eventId: Long, seatFields: Map<String, String>) {
        hashOps.putAll(seatCacheKey(eventId), seatFields)
    }

    fun deleteSeatCache(eventId: Long) {
        redis.delete(seatCacheKey(eventId))
    }

    fun getSeatStatus(eventId: Long, seatId: Long): String? {
        return hashOps.get(seatCacheKey(eventId), seatId.toString())
    }

    fun getAllSeatFields(eventId: Long): Map<String, String> {
        return hashOps.entries(seatCacheKey(eventId))
    }

    fun markSeatReserved(eventId: Long, seatId: Long) {
        val current = getSeatStatus(eventId, seatId) ?: return
        val parts = current.split(":")
        if (parts.size >= 3) {
            hashOps.put(seatCacheKey(eventId), seatId.toString(), "${parts[0]}:${parts[1]}:RESERVED")
        }
    }

    fun markSeatAvailable(eventId: Long, seatId: Long) {
        val current = getSeatStatus(eventId, seatId) ?: return
        val parts = current.split(":")
        if (parts.size >= 3) {
            hashOps.put(seatCacheKey(eventId), seatId.toString(), "${parts[0]}:${parts[1]}:AVAILABLE")
        }
    }

    fun tryHoldSeat(eventId: Long, seatId: Long, userId: String, nowMs: Long, ttlMs: Long): Boolean {
        val result = redis.execute(
            tryHoldScript,
            listOf(seatCacheKey(eventId)),
            seatId.toString(), userId, nowMs.toString(), ttlMs.toString()
        )
        return result == 1L
    }

    fun releaseHold(eventId: Long, seatId: Long, userId: String): Boolean {
        val result = redis.execute(
            releaseHoldScript,
            listOf(seatCacheKey(eventId)),
            seatId.toString(), userId
        )
        return result == 1L
    }
}

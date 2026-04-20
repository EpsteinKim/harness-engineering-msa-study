package com.epstein.practice.reserveservice.main.cache

import com.epstein.practice.reserveservice.type.constant.waitingKey
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository

@Repository
class QueueCacheRepository(
    private val redis: StringRedisTemplate
) {
    private val zSetOps = redis.opsForZSet()
    private val hashOps = redis.opsForHash<String, String>()

    // === Waiting Queue ===

    fun addToQueue(eventId: Long, userId: String, score: Double) {
        zSetOps.add(waitingKey(eventId), userId, score)
    }

    fun removeFromQueue(eventId: Long, userId: String): Long {
        return zSetOps.remove(waitingKey(eventId), userId) ?: 0
    }

    fun isInQueue(eventId: Long, userId: String): Boolean {
        return zSetOps.score(waitingKey(eventId), userId) != null
    }

    fun getQueuePosition(eventId: Long, userId: String): Long? {
        return zSetOps.rank(waitingKey(eventId), userId)
    }

    // === Seat Hold (SEAT_PICK 전용) ===

    fun holdSeat(eventId: Long, userId: String, seatId: Long) {
        hashOps.put(seatHeldKey(eventId), userId, seatId.toString())
    }

    fun getHeldSeatId(eventId: Long, userId: String): Long? {
        return hashOps.get(seatHeldKey(eventId), userId)?.toLongOrNull()
    }

    fun releaseHeldSeat(eventId: Long, userId: String) {
        hashOps.delete(seatHeldKey(eventId), userId)
    }

    private fun seatHeldKey(eventId: Long) = "seat_held:$eventId"
}

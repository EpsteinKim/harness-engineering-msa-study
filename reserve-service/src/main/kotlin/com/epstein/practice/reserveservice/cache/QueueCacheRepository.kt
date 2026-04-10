package com.epstein.practice.reserveservice.cache

import com.epstein.practice.reserveservice.constant.metadataKey
import com.epstein.practice.reserveservice.constant.waitingKey
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

    fun peekQueue(eventId: Long, count: Long): Set<String> {
        return zSetOps.range(waitingKey(eventId), 0, count - 1) ?: emptySet()
    }

    fun getQueuePosition(eventId: Long, userId: String): Long? {
        return zSetOps.rank(waitingKey(eventId), userId)
    }

    // === Request Metadata ===

    fun saveMetadata(eventId: Long, userId: String, metadata: Map<String, String>) {
        hashOps.putAll(metadataKey(eventId, userId), metadata)
    }

    fun getMetadata(eventId: Long, userId: String): Map<String, String> {
        return hashOps.entries(metadataKey(eventId, userId))
    }

    fun deleteMetadata(eventId: Long, userId: String) {
        redis.delete(metadataKey(eventId, userId))
    }
}

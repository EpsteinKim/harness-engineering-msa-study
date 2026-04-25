package com.epstein.practice.reserveservice.main.cache

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository

@Repository
class SagaCacheRepository(
    private val redis: StringRedisTemplate
) {

    fun markActive(eventId: Long, userId: String) {
        try {
            redis.opsForSet().add(activeSagaKey(eventId), userId)
        } catch (_: Exception) { /* Redis 장애 시 무시 — DB가 source of truth */ }
    }

    fun removeActive(eventId: Long, userId: String) {
        try {
            redis.opsForSet().remove(activeSagaKey(eventId), userId)
        } catch (_: Exception) { }
    }

    fun isActive(eventId: Long, userId: String): Boolean? {
        return try {
            redis.opsForSet().isMember(activeSagaKey(eventId), userId)
        } catch (_: Exception) {
            null
        }
    }

    private fun activeSagaKey(eventId: Long) = "active_saga:$eventId"
}

package com.epstein.practice.coreservice.main.cache

import com.epstein.practice.coreservice.type.entity.User
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository

/**
 * User 정보를 Redis Hash로 캐시.
 * - core-service가 쓰기 (write-through)
 * - reserve-service가 읽기 (존재 확인용)
 */
@Repository
class UserCacheRepository(
    private val redis: StringRedisTemplate
) {
    private val hashOps = redis.opsForHash<String, String>()

    fun save(user: User) {
        hashOps.putAll(
            userKey(user.id),
            mapOf(
                "id" to user.id.toString(),
                "email" to user.email,
                "name" to user.name,
                "version" to user.version.toString()
            )
        )
    }

    fun delete(userId: Long) {
        redis.delete(userKey(userId))
    }

    fun exists(userId: Long): Boolean =
        redis.hasKey(userKey(userId)) == true

    companion object {
        fun userKey(userId: Long) = "user:$userId"
    }
}

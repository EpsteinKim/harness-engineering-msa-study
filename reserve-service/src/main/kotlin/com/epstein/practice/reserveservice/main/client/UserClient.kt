package com.epstein.practice.reserveservice.main.client

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient

/**
 * User 존재 검증. Redis 캐시 우선, miss 시 core-service HTTP fallback(캐시 하이드레이션).
 *
 * - Redis hit: 핫패스에서 HTTP 호출 없이 즉시 응답
 * - Redis miss: core-service의 GET /api/v1/users/{id} 호출 → 200이면 core-service가 캐시에 write-through
 *   다음 호출부터 hit
 */
@Component
class UserClient(
    private val userServiceClient: RestClient,
    private val redis: StringRedisTemplate,
) {
    private val logger = LoggerFactory.getLogger(UserClient::class.java)

    fun exists(userId: Long): Boolean {
        if (redis.hasKey(userKey(userId)) == true) return true
        return fallbackToHttp(userId)
    }

    private fun fallbackToHttp(userId: Long): Boolean =
        try {
            val ok = userServiceClient.get()
                .uri("/api/v1/users/{id}", userId)
                .retrieve()
                .toBodilessEntity()
                .statusCode
                .is2xxSuccessful
            if (ok) logger.debug("User cache miss hydrated via HTTP: userId={}", userId)
            ok
        } catch (e: HttpClientErrorException.NotFound) {
            false
        } catch (e: Exception) {
            logger.warn("core-service unreachable during user existence check: userId={}, {}", userId, e.message)
            false
        }

    companion object {
        fun userKey(userId: Long) = "user:$userId"
    }
}

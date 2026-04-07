package com.epstein.practice.queueservice.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class QueueService(
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${queue.callback.allowed-hosts}") allowedHostsConfig: String
) {
    private val registryKey = "queues"
    private val processingTimeoutMs = 10 * 60 * 1000L
    private val allowedHosts = allowedHostsConfig.split(",").map { it.trim() }

    fun enqueue(queueName: String, userId: String, callbackUrl: String, payload: Map<String, Any>) {
        val isAllowed = allowedHosts.any { callbackUrl.startsWith(it) }
        if (!isAllowed) {
            throw IllegalArgumentException("Callback URL not allowed: $callbackUrl")
        }
        val score = System.currentTimeMillis().toDouble()
        redis.opsForSet().add(registryKey, queueName)
        redis.opsForZSet().add("$queueName:waiting", userId, score)
        redis.opsForHash<String, String>().putAll("$queueName:request:$userId", mapOf(
            "callbackUrl" to callbackUrl,
            "payload" to objectMapper.writeValueAsString(payload)
        ))
    }

    fun dequeue(queueName: String, count: Long): Set<String> {
        val waitingKey = "$queueName:waiting"
        val processingKey = "$queueName:processing"
        val users = redis.opsForZSet().range(waitingKey, 0, count - 1) ?: emptySet()
        if (users.isNotEmpty()) {
            redis.opsForZSet().remove(waitingKey, *users.toTypedArray())
            val now = System.currentTimeMillis().toDouble()
            users.forEach { userId ->
                redis.opsForZSet().add(processingKey, userId, now)
            }
        }
        return users
    }

    fun getRequestMetadata(queueName: String, userId: String): RequestMetadata? {
        val data = redis.opsForHash<String, String>().entries("$queueName:request:$userId")
        if (data.isEmpty()) return null
        val callbackUrl = data["callbackUrl"] ?: return null
        val payloadJson = data["payload"] ?: return null
        return RequestMetadata(callbackUrl, payloadJson)
    }

    fun complete(queueName: String, userId: String): Boolean {
        val removed = redis.opsForZSet().remove("$queueName:processing", userId) ?: 0
        redis.delete("$queueName:request:$userId")
        return removed > 0
    }

    fun fail(queueName: String, userId: String): Boolean {
        val removed = redis.opsForZSet().remove("$queueName:processing", userId) ?: 0
        redis.delete("$queueName:request:$userId")
        return removed > 0
    }

    fun reEnqueueExpired(): Long {
        val queueNames = redis.opsForSet().members(registryKey) ?: emptySet()
        var totalReEnqueued = 0L
        val cutoff = (System.currentTimeMillis() - processingTimeoutMs).toDouble()
        for (queueName in queueNames) {
            val processingKey = "$queueName:processing"
            val waitingKey = "$queueName:waiting"
            val expired = redis.opsForZSet().rangeByScore(processingKey, 0.0, cutoff) ?: emptySet()
            if (expired.isNotEmpty()) {
                val now = System.currentTimeMillis().toDouble()
                expired.forEach { userId ->
                    redis.opsForZSet().add(waitingKey, userId, now)
                }
                redis.opsForZSet().removeRangeByScore(processingKey, 0.0, cutoff)
                totalReEnqueued += expired.size
            }
        }
        return totalReEnqueued
    }

    fun cancel(queueName: String, userId: String): Boolean {
        val removedFromWaiting = redis.opsForZSet().remove("$queueName:waiting", userId) ?: 0
        val removedFromProcessing = redis.opsForZSet().remove("$queueName:processing", userId) ?: 0
        redis.delete("$queueName:request:$userId")
        return (removedFromWaiting + removedFromProcessing) > 0
    }

    fun getActiveQueues(): Set<String> =
        redis.opsForSet().members(registryKey) ?: emptySet()
}

data class RequestMetadata(
    val callbackUrl: String,
    val payloadJson: String
)

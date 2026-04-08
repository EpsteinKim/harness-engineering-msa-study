package com.epstein.practice.reserveservice.service

import com.epstein.practice.reserveservice.dto.SeatDTO
import com.epstein.practice.reserveservice.dto.SectionAvailabilityResponse
import com.epstein.practice.reserveservice.repository.SeatRepository
import com.epstein.practice.reserveservice.repository.support.SeatQueryRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

@Service
class ReservationQueueService(
    private val redis: StringRedisTemplate,
    @Value("\${queue.processing-timeout-ms:600000}") private val processingTimeoutMs: Long,
    private val seatRepository: SeatRepository,
    private val seatQueryRepository: SeatQueryRepository
) {
    private val waitingKey = "reservation:waiting"
    private val processingKey = "reservation:processing"


    private fun requestKey(userId: String) = "reservation:request:$userId"

    fun enqueue(userId: String, eventId: Long, seatId: Long? = null, section: String? = null) {
        val score = System.currentTimeMillis().toDouble()
        redis.opsForZSet().add(waitingKey, userId, score)
        val metadata = mutableMapOf("eventId" to eventId.toString())
        seatId?.let { metadata["seatId"] = it.toString() }
        section?.let { metadata["section"] = it }
        redis.opsForHash<String, String>().putAll(requestKey(userId), metadata)
    }

    fun dequeue(count: Long): Set<String> {
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

    fun getSeatList(eventId: Long): List<SeatDTO> {
        return seatRepository.findByEventId(eventId).map { it.toDTO() }
    }

    fun getSectionAvailability(eventId: Long): List<SectionAvailabilityResponse> {
        return seatQueryRepository.countAvailableBySection(eventId)
    }

    fun getRequestData(userId: String): RequestData? {
        val data = redis.opsForHash<String, String>().entries(requestKey(userId))
        if (data.isEmpty()) return null
        val eventId = data["eventId"]?.toLongOrNull() ?: return null
        val seatId = data["seatId"]?.toLongOrNull()
        val section = data["section"]
        return RequestData(eventId, seatId, section)
    }

    fun complete(userId: String): Boolean {
        val removed = redis.opsForZSet().remove(processingKey, userId) ?: 0
        redis.delete(requestKey(userId))
        return removed > 0
    }

    fun fail(userId: String): Boolean {
        val removed = redis.opsForZSet().remove(processingKey, userId) ?: 0
        redis.delete(requestKey(userId))
        return removed > 0
    }

    fun reEnqueueExpired(): Long {
        val cutoff = (System.currentTimeMillis() - processingTimeoutMs).toDouble()
        val expired = redis.opsForZSet().rangeByScore(processingKey, 0.0, cutoff) ?: emptySet()
        if (expired.isEmpty()) return 0

        val now = System.currentTimeMillis().toDouble()
        expired.forEach { userId ->
            redis.opsForZSet().add(waitingKey, userId, now)
        }
        redis.opsForZSet().removeRangeByScore(processingKey, 0.0, cutoff)
        return expired.size.toLong()
    }

    fun cancel(userId: String): Boolean {
        val removedFromWaiting = redis.opsForZSet().remove(waitingKey, userId) ?: 0
        val removedFromProcessing = redis.opsForZSet().remove(processingKey, userId) ?: 0
        redis.delete(requestKey(userId))
        return (removedFromWaiting + removedFromProcessing) > 0
    }

    fun getPosition(userId: String): Long? =
        redis.opsForZSet().rank(waitingKey, userId)
}

data class RequestData(
    val eventId: Long,
    val seatId: Long? = null,
    val section: String? = null
)

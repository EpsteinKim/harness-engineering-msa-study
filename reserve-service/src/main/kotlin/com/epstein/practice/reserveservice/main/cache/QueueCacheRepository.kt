package com.epstein.practice.reserveservice.main.cache

import com.epstein.practice.common.cache.eventCacheKey
import com.epstein.practice.reserveservice.type.constant.waitingKey
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Repository

data class DispatchEntry(
    val userId: String,
    val score: Long,
    val seatId: Long?,
    val section: String?,
)

@Repository
class QueueCacheRepository(
    private val redis: StringRedisTemplate
) {
    private val zSetOps = redis.opsForZSet()
    private val hashOps = redis.opsForHash<String, String>()

    private val enqueueScript = DefaultRedisScript<Long>().apply {
        setLocation(ClassPathResource("redis/enqueue.lua"))
        resultType = Long::class.java
    }

    private val dispatchPopScript = DefaultRedisScript<List<*>>().apply {
        setLocation(ClassPathResource("redis/dispatch_pop.lua"))
        resultType = List::class.java
    }

    private val validateEnqueueScript = DefaultRedisScript<List<*>>().apply {
        setLocation(ClassPathResource("redis/validate_enqueue.lua"))
        resultType = List::class.java
    }

    // === Enqueue (Lua 원자적: 잔여석 확인 + 선차감 + ZADD + HSET) ===

    /**
     * 대기열 등록 + 잔여석 선차감을 원자적으로 수행.
     * @return 1 (성공), 0 (이미 대기열), -1 (잔여석 없음), -2 (섹션 매진)
     */
    fun enqueue(eventId: Long, userId: String, seatId: Long?, section: String?): Long {
        val dispatchValue = "${seatId ?: ""}|${section ?: ""}"
        return redis.execute(
            enqueueScript,
            listOf(waitingKey(eventId), dispatchKey(eventId), eventCacheKey(eventId)),
            userId, System.currentTimeMillis().toString(), dispatchValue, section ?: ""
        ) ?: 0L
    }

    // === Dispatch Pop (Lua 원자적: ZPOPMIN + HGET + HDEL) ===

    fun popForDispatch(eventId: Long, count: Long): List<DispatchEntry> {
        val raw = redis.execute(
            dispatchPopScript,
            listOf(waitingKey(eventId), dispatchKey(eventId), processingKey(eventId)),
            count.toString()
        ) ?: return emptyList()

        val result = mutableListOf<DispatchEntry>()
        val list = raw as List<*>
        var i = 0
        while (i + 2 < list.size) {
            val userId = list[i]?.toString() ?: ""
            val score = list[i + 1]?.toString()?.toLongOrNull() ?: 0L
            val data = list[i + 2]?.toString() ?: "|"
            val parts = data.split("|", limit = 2)
            val seatId = parts.getOrNull(0)?.toLongOrNull()
            val section = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
            result.add(DispatchEntry(userId, score, seatId, section))
            i += 3
        }
        return result
    }

    // === Validate Enqueue (Lua 원자적: EXISTS + ZSCORE + HGET) ===

    data class EnqueueValidation(
        val eventExists: Boolean,
        val inQueue: Boolean,
        val seatSelectionType: String,
    )

    fun validateEnqueue(eventId: Long, userId: String): EnqueueValidation {
        val raw = redis.execute(
            validateEnqueueScript,
            listOf(eventCacheKey(eventId), waitingKey(eventId)),
            userId
        ) as? List<*> ?: return EnqueueValidation(false, false, "")
        return EnqueueValidation(
            eventExists = raw.getOrNull(0)?.toString()?.toLongOrNull() == 1L,
            inQueue = raw.getOrNull(1)?.toString()?.toLongOrNull() == 1L,
            seatSelectionType = raw.getOrNull(2)?.toString() ?: "SECTION_SELECT",
        )
    }

    // === Queue Query ===

    fun isInQueue(eventId: Long, userId: String): Boolean {
        return zSetOps.score(waitingKey(eventId), userId) != null
    }

    fun getQueuePosition(eventId: Long, userId: String): Long? {
        return zSetOps.rank(waitingKey(eventId), userId)
    }

    fun removeFromQueue(eventId: Long, userId: String): Long {
        return zSetOps.remove(waitingKey(eventId), userId) ?: 0
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

    // === Dispatch Data 조회 (cancel 보상 시 section 정보 필요) ===

    fun getDispatchData(eventId: Long, userId: String): Pair<Long?, String?> {
        val data = hashOps.get(dispatchKey(eventId), userId) ?: return null to null
        val parts = data.split("|", limit = 2)
        val seatId = parts.getOrNull(0)?.toLongOrNull()
        val section = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
        return seatId to section
    }

    // === Processing (Kafka 발행 추적) ===

    fun removeFromProcessing(eventId: Long, userId: String) {
        hashOps.delete(processingKey(eventId), userId)
    }

    fun getProcessingEntries(eventId: Long): List<DispatchEntry> {
        val all = hashOps.entries(processingKey(eventId))
        return all.mapNotNull { (userId, value) ->
            val parts = value.split("|", limit = 3)
            val score = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val seatId = parts.getOrNull(1)?.toLongOrNull()
            val section = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }
            DispatchEntry(userId, score, seatId, section)
        }
    }

    fun recoverToQueue(eventId: Long, entry: DispatchEntry) {
        enqueue(eventId, entry.userId, entry.seatId, entry.section)
        hashOps.delete(processingKey(eventId), entry.userId)
    }

    private fun seatHeldKey(eventId: Long) = "seat_held:$eventId"
    private fun dispatchKey(eventId: Long) = "reservation:dispatch:$eventId"
    private fun processingKey(eventId: Long) = "reservation:processing:$eventId"
}

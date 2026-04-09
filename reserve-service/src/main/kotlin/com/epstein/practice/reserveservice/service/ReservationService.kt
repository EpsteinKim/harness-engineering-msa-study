package com.epstein.practice.reserveservice.service

import com.epstein.practice.reserveservice.constant.waitingKey
import com.epstein.practice.reserveservice.constant.eventCacheKey
import com.epstein.practice.reserveservice.constant.metadataKey
import com.epstein.practice.reserveservice.constant.sectionAvailableField
import com.epstein.practice.reserveservice.constant.seatCacheKey
import com.epstein.practice.reserveservice.constant.sectionTotalField
import com.epstein.practice.reserveservice.dto.SectionAvailabilityResponse
import com.epstein.practice.reserveservice.repository.SeatRepository
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

@Service
class ReservationService(
    private val redis: StringRedisTemplate,
    private val seatRepository: SeatRepository
) {
    fun enqueue(userId: String, eventId: Long, seatId: Long? = null, section: String? = null) {
        if (redis.hasKey(eventCacheKey(eventId)) != true) {
            throw IllegalStateException("Event is not open for reservations")
        }

        val hashOps = redis.opsForHash<String, String>()
        val remaining = hashOps.get(eventCacheKey(eventId), "remainingSeats")?.toLongOrNull() ?: 0
        if (remaining <= 0) {
            throw IllegalStateException("No remaining seats")
        }

        val selectionType = hashOps.get(eventCacheKey(eventId), "seatSelectionType") ?: "SECTION_SELECT"
        if (selectionType == "SEAT_PICK" && seatId == null) {
            throw IllegalArgumentException("SEAT_PICK events require a specific seatId")
        }
        if (selectionType == "SECTION_SELECT" && section == null) {
            throw IllegalArgumentException("SECTION_SELECT events require a section")
        }

        if (selectionType == "SEAT_PICK" && seatId != null) {
            val seatData = hashOps.get(seatCacheKey(eventId), seatId.toString())
            if (seatData == null) {
                throw IllegalStateException("Seat not found")
            }
            if (seatData.endsWith(":RESERVED")) {
                throw IllegalStateException("Seat is already reserved")
            }
        }

        val alreadyInQueue = redis.opsForZSet().score(waitingKey(eventId), userId) != null
        if (alreadyInQueue) {
            throw IllegalStateException("Already in queue")
        }

        val score = System.currentTimeMillis().toDouble()
        redis.opsForZSet().add(waitingKey(eventId), userId, score)
        val metadata = mutableMapOf("eventId" to eventId.toString())
        seatId?.let { metadata["seatId"] = it.toString() }
        section?.let { metadata["section"] = it }
        redis.opsForHash<String, String>().putAll(metadataKey(userId), metadata)
    }

    fun peekWaiting(eventId: Long, count: Long): Set<String> {
        return redis.opsForZSet().range(waitingKey(eventId), 0, count - 1) ?: emptySet()
    }

    fun removeFromWaiting(eventId: Long, userId: String) {
        redis.opsForZSet().remove(waitingKey(eventId), userId)
        redis.delete(metadataKey(userId))
    }

    fun getSectionAvailability(eventId: Long): List<SectionAvailabilityResponse> {
        val allFields = redis.opsForHash<String, String>().entries(eventCacheKey(eventId))
        if (allFields.isEmpty()) return emptyList()

        val sections = allFields.keys
            .filter { it.startsWith("section:") && it.endsWith(":available") }
            .map { it.removePrefix("section:").removeSuffix(":available") }

        return sections.map { section ->
            SectionAvailabilityResponse(
                section = section,
                availableCount = allFields[sectionAvailableField(section)]?.toLongOrNull() ?: 0,
                totalCount = allFields[sectionTotalField(section)]?.toLongOrNull() ?: 0
            )
        }.sortedBy { it.section }
    }

    fun getRequestData(userId: String): RequestData? {
        val data = redis.opsForHash<String, String>().entries(metadataKey(userId))
        if (data.isEmpty()) return null
        val eventId = data["eventId"]?.toLongOrNull() ?: return null
        val seatId = data["seatId"]?.toLongOrNull()
        val section = data["section"]
        return RequestData(eventId, seatId, section)
    }

    fun cancel(userId: String): Boolean {
        val data = getRequestData(userId) ?: return false
        val removed = redis.opsForZSet().remove(waitingKey(data.eventId), userId) ?: 0
        redis.delete(metadataKey(userId))
        if (removed > 0) {
            redis.opsForHash<String, String>()
                .increment(eventCacheKey(data.eventId), "remainingSeats", 1)
        }
        return removed > 0
    }

    fun getPosition(userId: String): Long? {
        val data = getRequestData(userId) ?: return null
        return redis.opsForZSet().rank(waitingKey(data.eventId), userId)
    }
}

data class RequestData(
    val eventId: Long,
    val seatId: Long? = null,
    val section: String? = null
)

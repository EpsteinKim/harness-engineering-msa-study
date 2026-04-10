package com.epstein.practice.reserveservice.service

import com.epstein.practice.common.exception.ServerException
import com.epstein.practice.reserveservice.cache.EventCacheRepository
import com.epstein.practice.reserveservice.cache.QueueCacheRepository
import com.epstein.practice.reserveservice.constant.ErrorCode
import org.springframework.stereotype.Service

@Service
class ReservationService(
    private val eventCache: EventCacheRepository,
    private val queueCache: QueueCacheRepository,
    private val seatService: SeatService
) {
    fun enqueue(userId: String, eventId: Long, seatId: Long? = null, section: String? = null) {
        if (!eventCache.exists(eventId)) {
            throw ServerException(message = "Event is not open for reservations", code = ErrorCode.EVENT_NOT_OPEN)
        }

        val remaining = eventCache.getRemainingSeats(eventId)
        if (remaining <= 0) {
            throw ServerException(message = "No remaining seats", code = ErrorCode.NO_REMAINING_SEATS)
        }

        val selectionType = eventCache.getSeatSelectionType(eventId)
        if (selectionType == "SEAT_PICK" && seatId == null) {
            throw ServerException(message = "SEAT_PICK events require a specific seatId", code = ErrorCode.INVALID_REQUEST)
        }
        if (selectionType == "SECTION_SELECT" && section == null) {
            throw ServerException(message = "SECTION_SELECT events require a section", code = ErrorCode.INVALID_REQUEST)
        }

        if (selectionType == "SEAT_PICK" && seatId != null) {
            val seatData = eventCache.getSeatStatus(eventId, seatId)
                ?: throw ServerException(message = "Seat not found", code = ErrorCode.SEAT_NOT_FOUND)
            if (seatData.endsWith(":RESERVED")) {
                throw ServerException(message = "Seat is already reserved", code = ErrorCode.SEAT_ALREADY_RESERVED)
            }
        }

        if (queueCache.isInQueue(eventId, userId)) {
            throw ServerException(message = "Already in queue", code = ErrorCode.ALREADY_IN_QUEUE)
        }

        val score = System.currentTimeMillis().toDouble()
        queueCache.addToQueue(eventId, userId, score)
        val metadata = mutableMapOf<String, String>()
        seatId?.let { metadata["seatId"] = it.toString() }
        section?.let { metadata["section"] = it }
        queueCache.saveMetadata(eventId, userId, metadata)
    }

    fun peekWaiting(eventId: Long, count: Long): Set<String> {
        return queueCache.peekQueue(eventId, count)
    }

    fun removeFromWaiting(eventId: Long, userId: String) {
        queueCache.removeFromQueue(eventId, userId)
        queueCache.deleteMetadata(eventId, userId)
    }

    fun getRequestData(eventId: Long, userId: String): RequestData? {
        val data = queueCache.getMetadata(eventId, userId)
        if (data.isEmpty()) return null
        val seatId = data["seatId"]?.toLongOrNull()
        val section = data["section"]
        return RequestData(eventId, seatId, section)
    }

    fun cancel(eventId: Long, userId: String): Boolean {
        val removed = queueCache.removeFromQueue(eventId, userId)
        queueCache.deleteMetadata(eventId, userId)

        val releaseResult = seatService.releaseSeat(eventId, userId.toLong())
        if (releaseResult.success) {
            eventCache.adjustSeatCounts(eventId, 1, releaseResult.section)
        }

        return removed > 0 || releaseResult.success
    }

    fun getPosition(eventId: Long, userId: String): Long? {
        return queueCache.getQueuePosition(eventId, userId)
    }
}

data class RequestData(
    val eventId: Long,
    val seatId: Long? = null,
    val section: String? = null
)

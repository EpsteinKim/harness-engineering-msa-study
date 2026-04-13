package com.epstein.practice.reserveservice.service

import com.epstein.practice.common.exception.ServerException
import com.epstein.practice.reserveservice.cache.EventCacheRepository
import com.epstein.practice.reserveservice.cache.QueueCacheRepository
import com.epstein.practice.reserveservice.client.UserClient
import com.epstein.practice.reserveservice.constant.ErrorCode
import com.epstein.practice.reserveservice.entity.EventStatus
import com.epstein.practice.reserveservice.repository.EventRepository
import com.epstein.practice.reserveservice.scheduler.DynamicScheduler
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class ReservationService(
    private val eventCache: EventCacheRepository,
    private val queueCache: QueueCacheRepository,
    private val seatService: SeatService,
    private val eventRepository: EventRepository,
    private val userClient: UserClient,
    @Lazy private val dynamicScheduler: DynamicScheduler,
    @Value("\${reserve.hold.ttl-millis}") private val holdTtlMs: Long,
) {
    fun enqueue(userId: String, eventId: Long, seatId: Long? = null, section: String? = null) {
        val userIdLong = userId.toLongOrNull()
            ?: throw ServerException(message = "사용자를 찾을 수 없습니다", code = ErrorCode.USER_NOT_FOUND)
        if (!userClient.exists(userIdLong)) {
            throw ServerException(message = "사용자를 찾을 수 없습니다", code = ErrorCode.USER_NOT_FOUND)
        }

        if (!eventCache.exists(eventId)) {
            throw ServerException(message = "이벤트가 예약 가능한 상태가 아닙니다", code = ErrorCode.EVENT_NOT_OPEN)
        }

        val remaining = eventCache.getRemainingSeats(eventId)
        if (remaining <= 0) {
            throw ServerException(message = "잔여 좌석이 없습니다", code = ErrorCode.NO_REMAINING_SEATS)
        }

        val selectionType = eventCache.getSeatSelectionType(eventId)
        if (selectionType == "SEAT_PICK" && seatId == null) {
            throw ServerException(message = "SEAT_PICK 이벤트는 좌석 ID가 필요합니다", code = ErrorCode.INVALID_REQUEST)
        }
        if (selectionType == "SECTION_SELECT" && section == null) {
            throw ServerException(message = "SECTION_SELECT 이벤트는 구역 정보가 필요합니다", code = ErrorCode.INVALID_REQUEST)
        }

        if (selectionType == "SECTION_SELECT" && section != null) {
            if (eventCache.getSectionAvailable(eventId, section) <= 0) {
                throw ServerException(message = "해당 구역은 매진되었습니다", code = ErrorCode.SECTION_FULL)
            }
        }

        if (queueCache.isInQueue(eventId, userId)) {
            throw ServerException(message = "이미 대기열에 등록되어 있습니다", code = ErrorCode.ALREADY_IN_QUEUE)
        }

        if (selectionType == "SEAT_PICK" && seatId != null) {
            val held = eventCache.tryHoldSeat(
                eventId = eventId,
                seatId = seatId,
                userId = userId,
                nowMs = System.currentTimeMillis(),
                ttlMs = holdTtlMs
            )
            if (!held) {
                throw ServerException(message = "좌석을 선택할 수 없습니다", code = ErrorCode.SEAT_UNAVAILABLE)
            }
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
        val metadata = queueCache.getMetadata(eventId, userId)
        val heldSeatId = metadata["seatId"]?.toLongOrNull()

        val removed = queueCache.removeFromQueue(eventId, userId)
        queueCache.deleteMetadata(eventId, userId)

        if (heldSeatId != null) {
            eventCache.releaseHold(eventId, heldSeatId, userId)
        }

        val releaseResult = seatService.releaseSeat(eventId, userId.toLong())
        if (releaseResult.success) {
            eventCache.adjustSeatCounts(eventId, 1, releaseResult.section)
            if (isTicketingStillOpen(eventId)) {
                dynamicScheduler.startProcessing(eventId)
            }
        }

        return removed > 0 || releaseResult.success
    }

    private fun isTicketingStillOpen(eventId: Long): Boolean {
        val event = eventRepository.findById(eventId).orElse(null) ?: return false
        if (event.status != EventStatus.OPEN) return false
        val closeTime = event.ticketCloseTime ?: return true
        return closeTime.isAfter(LocalDateTime.now())
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

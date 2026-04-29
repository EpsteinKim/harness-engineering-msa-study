package com.epstein.practice.reserveservice.main.service

import com.epstein.practice.common.exception.ServerException
import com.epstein.practice.reserveservice.main.cache.EventCacheRepository
import com.epstein.practice.reserveservice.main.cache.QueueCacheRepository
import com.epstein.practice.reserveservice.main.client.PaymentClient
import com.epstein.practice.reserveservice.main.client.UserClient
import com.epstein.practice.reserveservice.config.ReserveConfig
import com.epstein.practice.reserveservice.type.constant.ErrorCode
import com.epstein.practice.reserveservice.type.dto.MyReservationItem
import com.epstein.practice.reserveservice.main.repository.SeatRepository
import org.springframework.stereotype.Service
import java.time.ZonedDateTime

@Service
class ReservationService(
    private val eventCache: EventCacheRepository,
    private val queueCache: QueueCacheRepository,
    private val seatService: SeatService,
    private val seatRepository: SeatRepository,
    private val userClient: UserClient,
    private val paymentClient: PaymentClient,
    private val sagaOrchestrator: SagaOrchestrator,
) {
    fun enqueue(userId: String, eventId: Long, seatId: Long? = null, section: String? = null) {
        val userIdLong = userId.toLongOrNull()
            ?: throw ServerException(message = "사용자를 찾을 수 없습니다", code = ErrorCode.USER_NOT_FOUND)

        val selectionType = validateEnqueue(userIdLong, eventId, userId, seatId, section)
        holdSeatIfNeeded(eventId, seatId, userId, selectionType)

        val result = queueCache.enqueue(eventId, userId, seatId, section)
        when (result) {
            1L -> {
                if (seatId != null) queueCache.holdSeat(eventId, userId, seatId)
            }
            0L -> throw ServerException(message = "이미 대기열에 등록되어 있습니다", code = ErrorCode.ALREADY_IN_QUEUE)
            -1L -> throw ServerException(message = "잔여 좌석이 없습니다", code = ErrorCode.NO_REMAINING_SEATS)
            -2L -> throw ServerException(message = "해당 구역은 매진되었습니다", code = ErrorCode.SECTION_FULL)
            else -> throw ServerException(message = "대기열 등록에 실패했습니다", code = ErrorCode.INVALID_REQUEST)
        }
    }

    private fun validateEnqueue(userIdLong: Long, eventId: Long, userId: String, seatId: Long?, section: String?): String {
        val validation = queueCache.validateEnqueue(eventId, userId)
        if (!validation.eventExists) {
            throw ServerException(message = "이벤트가 예약 가능한 상태가 아닙니다", code = ErrorCode.EVENT_NOT_OPEN)
        }
        if (validation.inQueue) {
            throw ServerException(message = "이미 대기열에 등록되어 있습니다", code = ErrorCode.ALREADY_IN_QUEUE)
        }

        if (validation.alreadyReserved) {
            throw ServerException(message = "이미 해당 이벤트에 예약이 존재합니다", code = ErrorCode.ALREADY_RESERVED)
        }

        when (validation.seatSelectionType) {
            "SEAT_PICK" if seatId == null ->
                throw ServerException(message = "SEAT_PICK 이벤트는 좌석 ID가 필요합니다", code = ErrorCode.INVALID_REQUEST)

            "SECTION_SELECT" if section == null ->
                throw ServerException(message = "SECTION_SELECT 이벤트는 구역 정보가 필요합니다", code = ErrorCode.INVALID_REQUEST)
        }

        return validation.seatSelectionType
    }

    private fun holdSeatIfNeeded(eventId: Long, seatId: Long?, userId: String, selectionType: String) {
        if (seatId == null) return
        if (selectionType != "SEAT_PICK") return

        val held = eventCache.tryHoldSeat(
            eventId = eventId,
            seatId = seatId,
            userId = userId,
            nowMs = System.currentTimeMillis(),
            ttlMs = ReserveConfig.HOLD_TTL_MS
        )
        if (!held) {
            throw ServerException(message = "좌석을 선택할 수 없습니다", code = ErrorCode.SEAT_UNAVAILABLE)
        }
    }

    fun removeFromWaiting(eventId: Long, userId: String) {
        queueCache.removeFromQueue(eventId, userId)
        queueCache.releaseHeldSeat(eventId, userId)
    }

    fun cancel(eventId: Long, userId: String): Boolean {
        val heldSeatId = queueCache.getHeldSeatId(eventId, userId)
        val (_, section) = queueCache.getDispatchData(eventId, userId)

        val removed = queueCache.removeFromQueue(eventId, userId)
        queueCache.releaseHeldSeat(eventId, userId)

        if (removed > 0) {
            eventCache.adjustSeatCounts(eventId, 1, section)
        }

        if (heldSeatId != null) {
            eventCache.releaseHold(eventId, heldSeatId, userId)
        }

        val userIdLong = userId.toLongOrNull() ?: return removed > 0
        val saga = sagaOrchestrator.findActiveSaga(eventId, userIdLong)
        if (saga != null) {
            sagaOrchestrator.onCancel(saga.id)
            return true
        }

        return removed > 0
    }

    fun getPosition(eventId: Long, userId: String): Long? {
        return queueCache.getQueuePosition(eventId, userId)
    }

    fun getMyReservations(userId: Long): List<MyReservationItem> {
        val seats = seatRepository.findActiveByUserId(userId)
        if (seats.isEmpty()) return emptyList()

        val payments = paymentClient.listByUser(userId).filter { it.status in listOf("PENDING", "SUCCEEDED") }.associateBy { it.seatId }
        val eventFieldsCache = mutableMapOf<Long, Map<String, String>>()

        return seats.map { seat ->
            val p = payments[seat.id]
            val ef = eventFieldsCache.getOrPut(seat.eventId) { eventCache.getAllFields(seat.eventId) }
            MyReservationItem(
                eventId = seat.eventId,
                eventName = ef["name"] ?: "",
                eventTime = ef["eventTime"]?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() }
                    ?: ZonedDateTime.now(),
                seatId = seat.id,
                seatNumber = seat.seatNumber,
                section = seat.section,
                seatStatus = seat.status.name,
                priceAmount = seat.priceAmount,
                paymentId = p?.id,
                paymentStatus = p?.status,
                reservedAt = seat.reservedAt
            )
        }
    }
}

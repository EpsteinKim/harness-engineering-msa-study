package com.epstein.practice.reserveservice.main.service

import com.epstein.practice.common.exception.ServerException
import com.epstein.practice.reserveservice.main.cache.EventCacheRepository
import com.epstein.practice.reserveservice.main.cache.QueueCacheRepository
import com.epstein.practice.reserveservice.main.client.PaymentClient
import com.epstein.practice.reserveservice.main.client.UserClient
import com.epstein.practice.reserveservice.config.KafkaConfig
import com.epstein.practice.reserveservice.config.KafkaConfig.Companion.PARTITION_BUCKETS
import com.epstein.practice.reserveservice.config.ReserveConfig
import com.epstein.practice.reserveservice.type.constant.ErrorCode
import com.epstein.practice.reserveservice.type.dto.MyReservationItem
import com.epstein.practice.reserveservice.type.event.EnqueueMessage
import com.epstein.practice.reserveservice.main.repository.SeatRepository
import com.epstein.practice.common.outbox.OutboxService
import org.springframework.stereotype.Service
import kotlin.math.absoluteValue

@Service
class ReservationService(
    private val eventCache: EventCacheRepository,
    private val queueCache: QueueCacheRepository,
    private val seatService: SeatService,
    private val seatRepository: SeatRepository,
    private val userClient: UserClient,
    private val paymentClient: PaymentClient,
    private val outboxService: OutboxService,
    private val sagaOrchestrator: SagaOrchestrator,
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

        if (queueCache.isInQueue(eventId, userId)) {
            throw ServerException(message = "이미 대기열에 등록되어 있습니다", code = ErrorCode.ALREADY_IN_QUEUE)
        }

        val remaining = eventCache.getRemainingSeats(eventId)
        if (remaining <= 0) {
            throw ServerException(message = "잔여 좌석이 없습니다", code = ErrorCode.NO_REMAINING_SEATS)
        }

        val selectionType = eventCache.getSeatSelectionType(eventId)
        if (selectionType == "SEAT_PICK") {
            if (seatId == null) {
                throw ServerException(message = "SEAT_PICK 이벤트는 좌석 ID가 필요합니다", code = ErrorCode.INVALID_REQUEST)
            } else {
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
        } else if (selectionType == "SECTION_SELECT") {
            if (section == null) {
                throw ServerException(message = "SECTION_SELECT 이벤트는 구역 정보가 필요합니다", code = ErrorCode.INVALID_REQUEST)
            } else {
                if (eventCache.getSectionAvailable(eventId, section) <= 0) {
                    throw ServerException(message = "해당 구역은 매진되었습니다", code = ErrorCode.SECTION_FULL)
                }
            }
        }

        val now = System.currentTimeMillis()
        queueCache.addToQueue(eventId, userId, now.toDouble())
        if (seatId != null) {
            queueCache.holdSeat(eventId, userId, seatId)
        }

        val bucket = (userIdLong % PARTITION_BUCKETS).absoluteValue
        val key = "$eventId:$bucket"
        val message = EnqueueMessage(
            eventId = eventId,
            userId = userId,
            seatId = seatId,
            section = section,
            joinedAt = now
        )
        outboxService.save(KafkaConfig.TOPIC_RESERVE_QUEUE, key, message)
    }

    fun removeFromWaiting(eventId: Long, userId: String) {
        queueCache.removeFromQueue(eventId, userId)
        queueCache.releaseHeldSeat(eventId, userId)
    }

    fun cancel(eventId: Long, userId: String): Boolean {
        val heldSeatId = queueCache.getHeldSeatId(eventId, userId)

        val removed = queueCache.removeFromQueue(eventId, userId)
        queueCache.releaseHeldSeat(eventId, userId)

        if (heldSeatId != null) {
            eventCache.releaseHold(eventId, heldSeatId, userId)
        }

        val saga = sagaOrchestrator.findActiveSaga(eventId, userId.toLong())
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

        val payments = paymentClient.listByUser(userId).associateBy { it.seatId }
        val eventFieldsCache = mutableMapOf<Long, Map<String, String>>()

        return seats.map { seat ->
            val p = payments[seat.id]
            val ef = eventFieldsCache.getOrPut(seat.eventId) { eventCache.getAllFields(seat.eventId) }
            MyReservationItem(
                eventId = seat.eventId,
                eventName = ef["name"] ?: "",
                eventTime = ef["eventTime"]?.let { runCatching { java.time.ZonedDateTime.parse(it) }.getOrNull() }
                    ?: java.time.ZonedDateTime.now(),
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

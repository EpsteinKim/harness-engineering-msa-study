package com.epstein.practice.reserveservice.service

import com.epstein.practice.common.exception.ServerException
import com.epstein.practice.reserveservice.cache.EventCacheRepository
import com.epstein.practice.reserveservice.client.PaymentClient
import com.epstein.practice.reserveservice.constant.ErrorCode
import com.epstein.practice.reserveservice.dto.EventSummaryResponse
import com.epstein.practice.reserveservice.dto.MyReservationItem
import com.epstein.practice.reserveservice.entity.Event
import com.epstein.practice.reserveservice.entity.EventStatus
import com.epstein.practice.reserveservice.repository.EventRepository
import com.epstein.practice.reserveservice.repository.SeatRepository
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class EventService(
    private val eventRepository: EventRepository,
    private val seatRepository: SeatRepository,
    private val eventCache: EventCacheRepository,
    private val paymentClient: PaymentClient,
) {
    /**
     * OPEN 이벤트 목록 — Redis ZSET 인덱스 + HGETALL로 조립.
     * 캐시 miss(워밍 전 또는 장애) 시 DB fallback.
     */
    fun listEvents(status: EventStatus): List<EventSummaryResponse> {
        if (status == EventStatus.OPEN) {
            val ids = eventCache.getOpenEventIdsOrderedByTicketOpenTime()
            if (ids.isNotEmpty()) {
                return ids.mapNotNull { id -> fromCache(id) }
            }
        }
        // fallback: CLOSED/DELETED 조회 또는 OPEN 캐시 비어있음
        return eventRepository.findByStatusOrderByTicketOpenTimeAsc(status).map { toSummary(it) }
    }

    /**
     * 이벤트 단건 — Redis HGETALL 우선, 없으면 DB fallback.
     */
    fun getEvent(eventId: Long): EventSummaryResponse {
        fromCache(eventId)?.let { return it }

        val event = eventRepository.findById(eventId).orElseThrow {
            ServerException(message = "이벤트를 찾을 수 없습니다", code = ErrorCode.EVENT_NOT_OPEN)
        }
        return toSummary(event)
    }

    fun getMyReservations(userId: Long): List<MyReservationItem> {
        val seats = seatRepository.findActiveByUserId(userId)
        if (seats.isEmpty()) return emptyList()

        val payments = paymentClient.listByUser(userId).associateBy { it.seatId }

        return seats.map { seat ->
            val p = payments[seat.id]
            MyReservationItem(
                eventId = seat.event.id,
                eventName = seat.event.name,
                eventTime = seat.event.eventTime,
                seatId = seat.id,
                seatNumber = seat.seatNumber,
                section = seat.section,
                priceAmount = seat.priceAmount,
                paymentId = p?.id,
                paymentStatus = p?.status,
                reservedAt = seat.reservedAt
            )
        }
    }

    // === 캐시 ↔ 응답 변환 ===

    private fun fromCache(eventId: Long): EventSummaryResponse? {
        val fields = eventCache.getAllFields(eventId)
        if (fields.isEmpty()) return null
        return EventSummaryResponse(
            id = fields["id"]?.toLongOrNull() ?: eventId,
            name = fields["name"] ?: return null,
            eventTime = fields["eventTime"]?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return null,
            status = fields["status"] ?: "OPEN",
            ticketOpenTime = fields["ticketOpenTime"]?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
            ticketCloseTime = fields["ticketCloseTime"]?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
            seatSelectionType = fields["seatSelectionType"] ?: "SECTION_SELECT",
            remainingSeats = fields["remainingSeats"]?.toLongOrNull() ?: 0
        )
    }

    private fun toSummary(e: Event): EventSummaryResponse =
        EventSummaryResponse(
            id = e.id,
            name = e.name,
            eventTime = e.eventTime,
            status = e.status.name,
            ticketOpenTime = e.ticketOpenTime,
            ticketCloseTime = e.ticketCloseTime,
            seatSelectionType = e.seatSelectionType.name,
            remainingSeats = eventCache.getRemainingSeats(e.id)
        )
}

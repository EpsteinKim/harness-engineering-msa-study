package com.epstein.practice.coreservice.main.service

import com.epstein.practice.common.exception.ServerException
import com.epstein.practice.coreservice.type.constant.ErrorCode
import com.epstein.practice.coreservice.main.cache.EventCacheRepository
import com.epstein.practice.coreservice.type.dto.EventSummaryResponse
import com.epstein.practice.coreservice.type.entity.Event
import com.epstein.practice.coreservice.type.entity.EventStatus
import com.epstein.practice.coreservice.main.repository.EventRepository
import org.springframework.stereotype.Service
import java.time.ZonedDateTime
import java.time.ZoneId

@Service
class EventService(
    private val eventRepository: EventRepository,
    private val eventCache: EventCacheRepository,
) {
    fun listEvents(status: EventStatus): List<EventSummaryResponse> {
        if (status == EventStatus.OPEN) {
            val ids = eventCache.getOpenEventIdsOrderedByTicketOpenTime()
            if (ids.isNotEmpty()) {
                return ids.mapNotNull { id -> fromCache(id) }
            }
        }
        return eventRepository.findByStatusOrderByTicketOpenTimeAsc(status).map { toSummary(it) }
    }

    fun getEvent(eventId: Long): EventSummaryResponse {
        fromCache(eventId)?.let { return it }

        val event = eventRepository.findById(eventId).orElseThrow {
            ServerException(message = "이벤트를 찾을 수 없습니다", code = ErrorCode.EVENT_NOT_OPEN)
        }
        return toSummary(event)
    }

    private fun fromCache(eventId: Long): EventSummaryResponse? {
        val fields = eventCache.getAllFields(eventId)
        if (fields.isEmpty()) return null
        return EventSummaryResponse(
            id = fields["id"]?.toLongOrNull() ?: eventId,
            name = fields["name"] ?: return null,
            eventTime = fields["eventTime"]?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() } ?: return null,
            status = fields["status"] ?: "OPEN",
            ticketOpenTime = fields["ticketOpenTime"]?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() },
            ticketCloseTime = fields["ticketCloseTime"]?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() },
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

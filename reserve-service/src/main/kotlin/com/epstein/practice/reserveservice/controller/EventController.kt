package com.epstein.practice.reserveservice.controller

import com.epstein.practice.common.exception.ServerException
import com.epstein.practice.common.response.ApiResponse
import com.epstein.practice.reserveservice.constant.ErrorCode
import com.epstein.practice.reserveservice.dto.EventSummaryResponse
import com.epstein.practice.reserveservice.dto.MyReservationItem
import com.epstein.practice.reserveservice.entity.EventStatus
import com.epstein.practice.reserveservice.service.EventService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/reservations")
class EventController(
    private val eventService: EventService
) {
    @GetMapping("/events")
    fun listEvents(
        @RequestParam(defaultValue = "OPEN") status: String
    ): ApiResponse<List<EventSummaryResponse>> {
        val parsed = parseStatus(status)
        return ApiResponse.success(data = eventService.listEvents(parsed))
    }

    @GetMapping("/events/{eventId}")
    fun getEvent(@PathVariable eventId: Long): ApiResponse<EventSummaryResponse> =
        ApiResponse.success(data = eventService.getEvent(eventId))

    @GetMapping("/my")
    fun getMyReservations(@RequestParam userId: Long): ApiResponse<List<MyReservationItem>> =
        ApiResponse.success(data = eventService.getMyReservations(userId))

    private fun parseStatus(raw: String): EventStatus =
        runCatching { EventStatus.valueOf(raw.uppercase()) }.getOrElse {
            throw ServerException(
                message = "유효하지 않은 이벤트 상태입니다: $raw",
                code = ErrorCode.INVALID_REQUEST
            )
        }
}

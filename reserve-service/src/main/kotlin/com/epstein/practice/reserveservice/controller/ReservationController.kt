package com.epstein.practice.reserveservice.controller

import com.epstein.practice.common.exception.ServerException
import com.epstein.practice.common.response.ApiResponse
import com.epstein.practice.reserveservice.constant.ErrorCode
import com.epstein.practice.reserveservice.dto.EnqueueResponse
import com.epstein.practice.reserveservice.dto.ReservationRequest
import com.epstein.practice.reserveservice.service.ReservationService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/reservations")
class ReservationController(
    private val reserveService: ReservationService
) {
    @PostMapping
    fun enqueue(@RequestBody request: ReservationRequest): ApiResponse<EnqueueResponse> {
        if (request.seatId == null && request.section == null) {
            throw ServerException(message = "Either seatId or section must be provided", code = ErrorCode.INVALID_REQUEST)
        }

        if (request.section != null && (request.section.length != 1 || request.section[0] !in 'A'..'Z')) {
            throw ServerException(message = "Invalid section: must be A-Z", code = ErrorCode.INVALID_SECTION)
        }

        if (request.seatId != null) {
            reserveService.enqueue(request.userId, request.eventId, seatId = request.seatId)
        } else {
            reserveService.enqueue(request.userId, request.eventId, section = request.section)
        }

        val position = reserveService.getPosition(request.eventId, request.userId)
        return ApiResponse.success(
            data = EnqueueResponse(userId = request.userId, position = position),
            message = "Reservation request queued"
        )
    }

    @DeleteMapping("/queue/{eventId}/{userId}")
    fun cancel(@PathVariable eventId: Long, @PathVariable userId: String): ApiResponse<String> {
        val cancelled = reserveService.cancel(eventId, userId)
        if (!cancelled) {
            throw ServerException(message = "User not found in queue", code = ErrorCode.QUEUE_NOT_FOUND)
        }
        return ApiResponse.success(data = userId, message = "Cancelled")
    }
}

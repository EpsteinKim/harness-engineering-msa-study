package com.epstein.practice.reserveservice.controller

import com.epstein.practice.common.response.ApiResponse
import com.epstein.practice.reserveservice.dto.EnqueueResponse
import com.epstein.practice.reserveservice.dto.ReservationRequest
import com.epstein.practice.reserveservice.dto.SectionAvailabilityResponse
import com.epstein.practice.reserveservice.dto.SectionReservationRequest
import com.epstein.practice.reserveservice.service.ReservationService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
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
        try {
            if (request.seatId != null) {
                reserveService.enqueue(request.userId, request.eventId, seatId = request.seatId)
            } else if (request.section != null) {
                reserveService.enqueue(request.userId, request.eventId, section = request.section)
            } else {
                return ApiResponse.error(
                    message = "Either seatId or section must be provided",
                    code = "INVALID_REQUEST"
                )
            }
        } catch (e: IllegalStateException) {
            return ApiResponse.error(message = e.message ?: "Event not available", code = "EVENT_NOT_OPEN")
        } catch (e: IllegalArgumentException) {
            return ApiResponse.error(message = e.message ?: "Invalid request", code = "INVALID_REQUEST")
        }

        val position = reserveService.getPosition(request.userId)
        return ApiResponse.success(
            data = EnqueueResponse(userId = request.userId, position = position),
            message = "Reservation request queued"
        )
    }
    @PostMapping("/section")
    fun enqueueBySection(@RequestBody request: SectionReservationRequest): ApiResponse<EnqueueResponse> {
        if (request.section.length != 1 || request.section[0] !in 'A'..'Z') {
            return ApiResponse.error(message = "Invalid section: must be A-Z", code = "INVALID_SECTION")
        }
        try {
            reserveService.enqueue(request.userId, request.eventId, section = request.section)
        } catch (e: IllegalStateException) {
            return ApiResponse.error(message = e.message ?: "Event not available", code = "EVENT_NOT_OPEN")
        } catch (e: IllegalArgumentException) {
            return ApiResponse.error(message = e.message ?: "Invalid request", code = "INVALID_REQUEST")
        }
        val position = reserveService.getPosition(request.userId)
        return ApiResponse.success(
            data = EnqueueResponse(userId = request.userId, position = position),
            message = "Section reservation request queued"
        )
    }

    @GetMapping("/seats/{eventId}/sections")
    fun getSectionAvailability(@PathVariable eventId: Long): ApiResponse<List<SectionAvailabilityResponse>> {
        val availability = reserveService.getSectionAvailability(eventId)
        return ApiResponse.success(availability)
    }

    @DeleteMapping("/queue/{userId}")
    fun cancel(@PathVariable userId: String): ApiResponse<String> {
        val cancelled = reserveService.cancel(userId)
        if (!cancelled) {
            return ApiResponse.error(message = "User not found in queue", code = "QUEUE_NOT_FOUND")
        }
        return ApiResponse.success(data = userId, message = "Cancelled")
    }


}

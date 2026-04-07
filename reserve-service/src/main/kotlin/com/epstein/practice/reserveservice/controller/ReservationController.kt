package com.epstein.practice.reserveservice.controller

import com.epstein.practice.common.response.ApiResponse
import com.epstein.practice.reserveservice.dto.ReservationRequest
import com.epstein.practice.reserveservice.dto.ReservationResponse
import com.epstein.practice.reserveservice.service.SeatReservationService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/reservations")
class ReservationController(
    private val seatReservationService: SeatReservationService
) {
    @PostMapping
    fun reserve(@RequestBody request: ReservationRequest): ApiResponse<ReservationResponse> {
        val result = seatReservationService.reserveSeat(request.eventId, request.seatId, request.userId)
        val response = ReservationResponse(
            userId = result.userId,
            eventId = result.eventId,
            seatId = result.seatId,
            success = result.success,
            message = result.message
        )
        if (!result.success) {
            return ApiResponse.error(message = result.message, code = "RESERVATION_FAILED")
        }
        return ApiResponse.success(data = response)
    }
}

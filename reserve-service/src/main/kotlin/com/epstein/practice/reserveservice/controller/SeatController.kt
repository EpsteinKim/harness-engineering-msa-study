package com.epstein.practice.reserveservice.controller

import com.epstein.practice.common.response.ApiResponse
import com.epstein.practice.reserveservice.dto.SeatMapEntry
import com.epstein.practice.reserveservice.dto.SectionAvailabilityResponse
import com.epstein.practice.reserveservice.service.SeatService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/reservations")
class SeatController(
    private val seatService: SeatService
) {
    @GetMapping("/seats/{eventId}/sections")
    fun getSectionAvailability(@PathVariable eventId: Long): ApiResponse<List<SectionAvailabilityResponse>> {
        val availability = seatService.getSectionAvailability(eventId)
        return ApiResponse.success(availability)
    }

    @GetMapping("/seats/{eventId}")
    fun getSeatMap(
        @PathVariable eventId: Long,
        @RequestParam(required = false) section: String?
    ): ApiResponse<List<SeatMapEntry>> {
        val seatMap = seatService.getSeatMap(eventId, section)
        return ApiResponse.success(seatMap)
    }
}

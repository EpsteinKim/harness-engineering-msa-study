package com.epstein.practice.reserveservice.controller

import com.epstein.practice.common.exception.ServerException
import com.epstein.practice.common.response.ApiResponse
import com.epstein.practice.reserveservice.constant.ErrorCode
import com.epstein.practice.reserveservice.dto.EnqueueResponse
import com.epstein.practice.reserveservice.dto.PaymentRequest
import com.epstein.practice.reserveservice.dto.ReservationRequest
import com.epstein.practice.reserveservice.service.PaymentOrchestrationResult
import com.epstein.practice.reserveservice.service.PaymentOrchestrator
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
    private val reserveService: ReservationService,
    private val paymentOrchestrator: PaymentOrchestrator
) {
    @PostMapping
    fun enqueue(@RequestBody request: ReservationRequest): ApiResponse<EnqueueResponse> {
        if (request.seatId == null && request.section == null) {
            throw ServerException(message = "좌석 ID 또는 구역을 입력해야 합니다", code = ErrorCode.INVALID_REQUEST)
        }

        if (request.section != null && (request.section.length != 1 || request.section[0] !in 'A'..'Z')) {
            throw ServerException(message = "유효하지 않은 구역입니다: A-Z 한 글자여야 합니다", code = ErrorCode.INVALID_SECTION)
        }

        if (request.seatId != null) {
            reserveService.enqueue(request.userId, request.eventId, seatId = request.seatId)
        } else {
            reserveService.enqueue(request.userId, request.eventId, section = request.section)
        }

        val position = reserveService.getPosition(request.eventId, request.userId)
        return ApiResponse.success(
            data = EnqueueResponse(userId = request.userId, position = position),
            message = "예약 요청이 대기열에 추가되었습니다"
        )
    }

    @DeleteMapping("/queue/{eventId}/{userId}")
    fun cancel(@PathVariable eventId: Long, @PathVariable userId: String): ApiResponse<String> {
        val cancelled = reserveService.cancel(eventId, userId)
        if (!cancelled) {
            throw ServerException(message = "대기열에 해당 유저가 없습니다", code = ErrorCode.QUEUE_NOT_FOUND)
        }
        return ApiResponse.success(data = userId, message = "취소되었습니다")
    }

    @PostMapping("/pay")
    fun pay(@RequestBody request: PaymentRequest): ApiResponse<PaymentOrchestrationResult> {
        val result = paymentOrchestrator.pay(
            eventId = request.eventId,
            userId = request.userId,
            amount = request.amount,
            method = request.method
        )
        return ApiResponse.success(data = result, message = result.message)
    }
}

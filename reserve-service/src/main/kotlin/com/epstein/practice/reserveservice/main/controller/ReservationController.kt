package com.epstein.practice.reserveservice.main.controller

import com.epstein.practice.common.exception.ServerException
import com.epstein.practice.common.response.ApiResponse
import com.epstein.practice.reserveservice.type.constant.ErrorCode
import com.epstein.practice.reserveservice.type.dto.EnqueueResponse
import com.epstein.practice.reserveservice.type.dto.MyReservationItem
import com.epstein.practice.reserveservice.type.dto.PaymentRequest
import com.epstein.practice.reserveservice.type.dto.QueuePositionResponse
import com.epstein.practice.reserveservice.type.dto.ReservationRequest
import com.epstein.practice.reserveservice.type.dto.SagaStatusResponse
import com.epstein.practice.reserveservice.producer.PaymentInitiator
import com.epstein.practice.reserveservice.main.service.ReservationService
import com.epstein.practice.reserveservice.main.service.SagaOrchestrator
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/reservations")
class ReservationController(
    private val reserveService: ReservationService,
    private val paymentInitiator: PaymentInitiator,
    private val sagaOrchestrator: SagaOrchestrator,
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

    @GetMapping("/queue/{eventId}/{userId}")
    fun getQueuePosition(
        @PathVariable eventId: Long,
        @PathVariable userId: String
    ): ApiResponse<QueuePositionResponse> {
        val position = reserveService.getPosition(eventId, userId)
        val inQueue = position != null && position >= 0
        return ApiResponse.success(
            data = QueuePositionResponse(
                position = if (inQueue) position else null,
                inQueue = inQueue
            )
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
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun pay(@RequestBody request: PaymentRequest): ApiResponse<Map<String, Long>> {
        val result = paymentInitiator.requestPayment(
            eventId = request.eventId,
            userId = request.userId,
            method = request.method
        )
        return ApiResponse.success(
            data = mapOf("seatId" to result.seatId, "sagaId" to result.sagaId),
            message = "결제 요청이 접수되었습니다"
        )
    }

    @GetMapping("/saga/{sagaId}")
    fun getSagaStatus(@PathVariable sagaId: Long): ApiResponse<SagaStatusResponse> {
        val saga = sagaOrchestrator.getSaga(sagaId)
            ?: throw ServerException(message = "예약 정보를 찾을 수 없습니다", code = ErrorCode.QUEUE_NOT_FOUND)
        return ApiResponse.success(
            data = SagaStatusResponse(
                sagaId = saga.id,
                eventId = saga.eventId,
                userId = saga.userId,
                seatId = saga.seatId,
                paymentId = saga.paymentId,
                step = saga.step.name,
                status = saga.status.name,
            )
        )
    }

    @GetMapping("/my")
    fun getMyReservations(@RequestParam userId: Long): ApiResponse<List<MyReservationItem>> =
        ApiResponse.success(data = reserveService.getMyReservations(userId))
}

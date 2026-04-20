package com.epstein.practice.paymentservice.main.controller

import com.epstein.practice.common.response.ApiResponse
import com.epstein.practice.paymentservice.type.dto.PaymentResponse
import com.epstein.practice.paymentservice.main.service.PaymentService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 결제 처리는 Kafka 이벤트(`payment.events`)로 구동된다. 이 컨트롤러는 조회 전용.
 */
@RestController
@RequestMapping("/api/v1/payments")
class PaymentController(
    private val paymentService: PaymentService
) {
    @GetMapping("/health")
    fun health(): ApiResponse<String> = ApiResponse.success(data = "payment-service")

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): ApiResponse<PaymentResponse> =
        ApiResponse.success(data = PaymentResponse.from(paymentService.getById(id)))

    @GetMapping
    fun listByUser(@RequestParam userId: Long): ApiResponse<List<PaymentResponse>> =
        ApiResponse.success(data = paymentService.getByUserId(userId).map(PaymentResponse::from))
}

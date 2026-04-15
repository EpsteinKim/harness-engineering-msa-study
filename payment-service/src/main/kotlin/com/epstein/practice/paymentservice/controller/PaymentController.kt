package com.epstein.practice.paymentservice.controller

import com.epstein.practice.common.response.ApiResponse
import com.epstein.practice.paymentservice.dto.PaymentRequest
import com.epstein.practice.paymentservice.dto.PaymentResponse
import com.epstein.practice.paymentservice.service.PaymentService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/payments")
class PaymentController(
    private val paymentService: PaymentService
) {
    @GetMapping("/health")
    fun health(): ApiResponse<String> = ApiResponse.success(data = "payment-service")

    @PostMapping
    fun process(@RequestBody request: PaymentRequest): ApiResponse<PaymentResponse> {
        val payment = paymentService.processPayment(request)
        return ApiResponse.success(
            data = PaymentResponse.from(payment),
            message = if (payment.status.name == "SUCCEEDED") "결제가 완료되었습니다" else "결제가 실패했습니다"
        )
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): ApiResponse<PaymentResponse> =
        ApiResponse.success(data = PaymentResponse.from(paymentService.getById(id)))

    @GetMapping
    fun listByUser(@RequestParam userId: Long): ApiResponse<List<PaymentResponse>> =
        ApiResponse.success(data = paymentService.getByUserId(userId).map(PaymentResponse::from))
}

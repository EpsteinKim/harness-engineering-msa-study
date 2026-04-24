package com.epstein.practice.paymentservice.main.controller

import com.epstein.practice.common.exception.GlobalExceptionHandler
import com.epstein.practice.common.exception.ServerException
import com.epstein.practice.paymentservice.type.constant.ErrorCode
import com.epstein.practice.paymentservice.type.entity.Payment
import com.epstein.practice.paymentservice.type.entity.PaymentStatus
import com.epstein.practice.paymentservice.main.service.PaymentService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.ZonedDateTime

@ExtendWith(MockitoExtension::class)
class PaymentControllerTest {

    @Mock
    lateinit var paymentService: PaymentService

    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(PaymentController(paymentService))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    @Test
    @DisplayName("GET /api/v1/payments/{id} - 404")
    fun getNotFound() {
        `when`(paymentService.getById(999L)).thenThrow(
            ServerException(message = "결제 정보를 찾을 수 없습니다", code = ErrorCode.PAYMENT_NOT_FOUND)
        )

        mockMvc.perform(get("/api/v1/payments/999"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"))
    }

    @Test
    @DisplayName("GET /api/v1/payments?userId=X - 유저 결제 목록")
    fun listByUser() {
        val payment = Payment(
            id = 1L, seatId = 10L, userId = 1L, eventId = 1L,
            amount = 10000L, method = "CARD",
            status = PaymentStatus.SUCCEEDED,
            completedAt = ZonedDateTime.now()
        )
        `when`(paymentService.getByUserId(1L)).thenReturn(listOf(payment))

        mockMvc.perform(get("/api/v1/payments").param("userId", "1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(1))
            .andExpect(jsonPath("$.data[0].seatId").value(10))
    }
}

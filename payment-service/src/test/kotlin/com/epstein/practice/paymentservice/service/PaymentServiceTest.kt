package com.epstein.practice.paymentservice.service

import com.epstein.practice.common.exception.ServerException
import com.epstein.practice.paymentservice.dto.PaymentRequest
import com.epstein.practice.paymentservice.entity.Payment
import com.epstein.practice.paymentservice.entity.PaymentStatus
import com.epstein.practice.paymentservice.repository.PaymentRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.lenient
import org.mockito.junit.jupiter.MockitoExtension
import java.util.Optional
import kotlin.random.Random

@ExtendWith(MockitoExtension::class)
class PaymentServiceTest {

    @Mock
    lateinit var paymentRepository: PaymentRepository

    private lateinit var service: PaymentService

    private fun buildService(rate: Double, nextDouble: Double) {
        val fixedRandom = object : Random() {
            override fun nextBits(bitCount: Int): Int = 0
            override fun nextDouble(): Double = nextDouble
        }
        service = PaymentService(paymentRepository, rate, fixedRandom)
        lenient().`when`(paymentRepository.save(org.mockito.ArgumentMatchers.any(Payment::class.java)))
            .thenAnswer { inv -> inv.getArgument<Payment>(0) }
    }

    @Test
    @DisplayName("랜덤값이 성공률보다 작으면 SUCCEEDED 상태로 저장된다")
    fun succeededWhenRandomBelowRate() {
        buildService(rate = 0.7, nextDouble = 0.5)
        val result = service.processPayment(
            PaymentRequest(userId = 1L, seatId = 10L, eventId = 1L, amount = 10000L, method = "CARD")
        )
        assertEquals(PaymentStatus.SUCCEEDED, result.status)
        assertNotNull(result.completedAt)
    }

    @Test
    @DisplayName("랜덤값이 성공률보다 크면 FAILED 상태로 저장된다")
    fun failedWhenRandomAboveRate() {
        buildService(rate = 0.7, nextDouble = 0.9)
        val result = service.processPayment(
            PaymentRequest(userId = 1L, seatId = 10L, eventId = 1L, amount = 10000L, method = "CARD")
        )
        assertEquals(PaymentStatus.FAILED, result.status)
    }

    @Test
    @DisplayName("지원하지 않는 method면 INVALID_METHOD 예외")
    fun invalidMethodThrows() {
        buildService(rate = 0.7, nextDouble = 0.5)
        val exception = assertThrows(ServerException::class.java) {
            service.processPayment(
                PaymentRequest(userId = 1L, seatId = 10L, eventId = 1L, amount = 10000L, method = "BITCOIN")
            )
        }
        assertEquals("INVALID_METHOD", exception.code)
    }

    @Test
    @DisplayName("getById - 존재하지 않으면 PAYMENT_NOT_FOUND")
    fun getByIdNotFound() {
        buildService(rate = 0.7, nextDouble = 0.5)
        `when`(paymentRepository.findById(999L)).thenReturn(Optional.empty())
        val exception = assertThrows(ServerException::class.java) { service.getById(999L) }
        assertEquals("PAYMENT_NOT_FOUND", exception.code)
    }

    @Test
    @DisplayName("getByUserId - 유저 결제 목록 최신순 반환")
    fun getByUserId() {
        buildService(rate = 0.7, nextDouble = 0.5)
        val payment = Payment(
            id = 1L, seatId = 10L, userId = 1L, eventId = 1L,
            amount = 10000L, method = "CARD", status = PaymentStatus.SUCCEEDED
        )
        `when`(paymentRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(listOf(payment))

        val result = service.getByUserId(1L)

        assertEquals(1, result.size)
        assertEquals(10L, result[0].seatId)
    }
}

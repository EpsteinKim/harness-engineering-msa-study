package com.epstein.practice.paymentservice.service

import com.epstein.practice.common.exception.ServerException
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
import org.mockito.ArgumentMatchers
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class PaymentServiceTest {

    @Mock
    lateinit var paymentRepository: PaymentRepository

    private lateinit var service: PaymentService

    @BeforeEach
    fun setUp() {
        service = PaymentService(paymentRepository)
        lenient().`when`(paymentRepository.save(ArgumentMatchers.any(Payment::class.java)))
            .thenAnswer { inv -> inv.getArgument<Payment>(0) }
    }

    @Test
    @DisplayName("getById - 존재하지 않으면 PAYMENT_NOT_FOUND")
    fun getByIdNotFound() {
        `when`(paymentRepository.findById(999L)).thenReturn(Optional.empty())
        val exception = assertThrows(ServerException::class.java) { service.getById(999L) }
        assertEquals("PAYMENT_NOT_FOUND", exception.code)
    }

    @Test
    @DisplayName("getByUserId - 유저 결제 목록 최신순 반환")
    fun getByUserId() {
        val payment = Payment(
            id = 1L, seatId = 10L, userId = 1L, eventId = 1L,
            amount = 10000L, method = "CARD", status = PaymentStatus.SUCCEEDED
        )
        `when`(paymentRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(listOf(payment))

        val result = service.getByUserId(1L)

        assertEquals(1, result.size)
        assertEquals(10L, result[0].seatId)
    }

    @Test
    @DisplayName("createPendingForSeat - 기존 PENDING 없으면 새로 생성, method=null")
    fun createPendingNew() {
        `when`(paymentRepository.findBySeatIdAndStatus(10L, PaymentStatus.PENDING)).thenReturn(null)

        val result = service.createPendingForSeat(seatId = 10L, userId = 1L, eventId = 1L, amount = 200000L)

        assertEquals(PaymentStatus.PENDING, result.status)
        assertEquals(10L, result.seatId)
        assertEquals(200000L, result.amount)
        assertNull(result.method)
    }

    @Test
    @DisplayName("createPendingForSeat - 멱등: 동일 seatId PENDING 이미 있으면 skip")
    fun createPendingIdempotent() {
        val existing = Payment(
            id = 100L, seatId = 10L, userId = 1L, eventId = 1L,
            amount = 200000L, method = null, status = PaymentStatus.PENDING
        )
        `when`(paymentRepository.findBySeatIdAndStatus(10L, PaymentStatus.PENDING)).thenReturn(existing)

        val result = service.createPendingForSeat(seatId = 10L, userId = 1L, eventId = 1L, amount = 200000L)

        assertEquals(100L, result.id)
    }
}

package com.epstein.practice.paymentservice.service

import com.epstein.practice.common.event.PaymentRequested
import com.epstein.practice.common.outbox.OutboxService
import com.epstein.practice.paymentservice.config.KafkaConfig
import com.epstein.practice.paymentservice.producer.PaymentProcessingService
import com.epstein.practice.paymentservice.type.entity.Payment
import com.epstein.practice.paymentservice.type.entity.PaymentStatus
import com.epstein.practice.paymentservice.main.repository.PaymentRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import kotlin.random.Random

@ExtendWith(MockitoExtension::class)
class PaymentProcessingServiceTest {

    @Mock
    lateinit var paymentRepository: PaymentRepository

    @Mock
    lateinit var outboxService: OutboxService

    private lateinit var service: PaymentProcessingService

    private fun buildService(rate: Double, nextDouble: Double) {
        val fixedRandom = object : Random() {
            override fun nextBits(bitCount: Int): Int = 0
            override fun nextDouble(): Double = nextDouble
        }
        service = PaymentProcessingService(paymentRepository, outboxService, rate, fixedRandom)
    }

    private fun pending(seatId: Long = 10L) = Payment(
        id = 100L, seatId = seatId, userId = 1L, eventId = 1L,
        amount = 200000L, method = null, status = PaymentStatus.PENDING
    )

    @Test
    @DisplayName("PENDING 없으면 skip")
    fun noPendingSkips() {
        buildService(rate = 0.7, nextDouble = 0.5)
        `when`(paymentRepository.findBySeatIdAndStatus(10L, PaymentStatus.PENDING)).thenReturn(null)

        service.process(PaymentRequested(seatId = 10L, userId = 1L, method = "CARD"))

        verify(outboxService, never()).save(any() ?: "", any(), any() ?: Any())
    }

    @Test
    @DisplayName("유효하지 않은 method는 FAILED로 전이 + PaymentFailed 발행")
    fun invalidMethodFailsAndPublishes() {
        buildService(rate = 0.7, nextDouble = 0.5)
        val payment = pending()
        `when`(paymentRepository.findBySeatIdAndStatus(10L, PaymentStatus.PENDING)).thenReturn(payment)

        service.process(PaymentRequested(seatId = 10L, userId = 1L, method = "BITCOIN"))

        assertEquals(PaymentStatus.FAILED, payment.status)
        verify(outboxService).save(eq(KafkaConfig.TOPIC_PAYMENT_EVENTS) ?: "", eq("10"), any() ?: Any())
    }

    @Test
    @DisplayName("random < rate 면 SUCCEEDED + PaymentSucceeded 발행")
    fun succeededPublishes() {
        buildService(rate = 0.7, nextDouble = 0.5)
        val payment = pending()
        `when`(paymentRepository.findBySeatIdAndStatus(10L, PaymentStatus.PENDING)).thenReturn(payment)

        service.process(PaymentRequested(seatId = 10L, userId = 1L, method = "CARD"))

        assertEquals(PaymentStatus.SUCCEEDED, payment.status)
        assertEquals("CARD", payment.method)
        assertNotNull(payment.completedAt)
        verify(outboxService).save(eq(KafkaConfig.TOPIC_PAYMENT_EVENTS) ?: "", eq("10"), any() ?: Any())
    }

    @Test
    @DisplayName("random >= rate 면 FAILED + PaymentFailed 발행")
    fun failedPublishes() {
        buildService(rate = 0.7, nextDouble = 0.9)
        val payment = pending()
        `when`(paymentRepository.findBySeatIdAndStatus(10L, PaymentStatus.PENDING)).thenReturn(payment)

        service.process(PaymentRequested(seatId = 10L, userId = 1L, method = "CARD"))

        assertEquals(PaymentStatus.FAILED, payment.status)
        verify(outboxService).save(eq(KafkaConfig.TOPIC_PAYMENT_EVENTS) ?: "", eq("10"), any() ?: Any())
    }
}

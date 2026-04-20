package com.epstein.practice.paymentservice.consumer

import com.epstein.practice.common.event.HoldExpired
import com.epstein.practice.common.event.SeatHeld
import com.epstein.practice.common.event.SeatReleaseReason
import com.epstein.practice.common.event.SeatReleased
import com.epstein.practice.paymentservice.main.service.PaymentService
import com.epstein.practice.paymentservice.producer.PaymentTerminationService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class SeatEventConsumerTest {

    @Mock
    lateinit var paymentService: PaymentService

    @Mock
    lateinit var terminationService: PaymentTerminationService

    private lateinit var consumer: SeatEventConsumer

    @BeforeEach
    fun setUp() {
        consumer = SeatEventConsumer(paymentService, terminationService)
    }

    @Test
    @DisplayName("SeatHeld 수신 시 PaymentService.createPendingForSeat 호출")
    fun onSeatHeldCreatesPendingPayment() {
        val event = SeatHeld(
            seatId = 100L,
            userId = 5L,
            eventId = 1L,
            section = "A",
            amount = 200_000L,
            heldUntilMs = System.currentTimeMillis() + 600_000L
        )

        consumer.onSeatHeld(event)

        verify(paymentService).createPendingForSeat(
            seatId = 100L,
            userId = 5L,
            eventId = 1L,
            amount = 200_000L
        )
    }

    @Test
    @DisplayName("HoldExpired 수신 시 PaymentTerminationService.expirePending 호출")
    fun onHoldExpiredExpiresPayment() {
        consumer.onHoldExpired(HoldExpired(seatId = 100L, userId = 5L, eventId = 1L))
        verify(terminationService).expirePending(100L)
    }

    @Test
    @DisplayName("SeatReleased(CANCELLED) 수신 시 cancelPending 호출")
    fun onSeatReleasedCancelledCancelsPayment() {
        consumer.onSeatReleased(
            SeatReleased(seatId = 100L, userId = 5L, eventId = 1L, reason = SeatReleaseReason.CANCELLED)
        )
        verify(terminationService).cancelPending(100L)
    }

    @Test
    @DisplayName("SeatReleased(PAYMENT_FAILED) 수신 시 termination 호출 없음")
    fun onSeatReleasedPaymentFailedSkips() {
        consumer.onSeatReleased(
            SeatReleased(seatId = 100L, userId = 5L, eventId = 1L, reason = SeatReleaseReason.PAYMENT_FAILED)
        )
        verify(terminationService, never()).cancelPending(100L)
        verify(terminationService, never()).expirePending(100L)
    }
}

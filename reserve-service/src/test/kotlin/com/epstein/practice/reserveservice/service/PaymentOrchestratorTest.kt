package com.epstein.practice.reserveservice.service

import com.epstein.practice.common.exception.ServerException
import com.epstein.practice.reserveservice.cache.EventCacheRepository
import com.epstein.practice.reserveservice.client.PaymentClient
import com.epstein.practice.reserveservice.client.PaymentProcessRequest
import com.epstein.practice.reserveservice.client.PaymentProcessResult
import com.epstein.practice.reserveservice.entity.Event
import com.epstein.practice.reserveservice.entity.EventStatus
import com.epstein.practice.reserveservice.entity.Seat
import com.epstein.practice.reserveservice.entity.SeatStatus
import com.epstein.practice.reserveservice.repository.EventRepository
import com.epstein.practice.reserveservice.repository.SeatRepository
import com.epstein.practice.reserveservice.scheduler.DynamicScheduler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import java.time.LocalDateTime
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class PaymentOrchestratorTest {

    @Mock
    lateinit var seatRepository: SeatRepository

    @Mock
    lateinit var paymentClient: PaymentClient

    @Mock
    lateinit var eventCache: EventCacheRepository

    @Mock
    lateinit var eventRepository: EventRepository

    @Mock
    lateinit var dynamicScheduler: DynamicScheduler

    private lateinit var orchestrator: PaymentOrchestrator
    private lateinit var event: Event

    @BeforeEach
    fun setUp() {
        orchestrator = PaymentOrchestrator(
            seatRepository, paymentClient, eventCache, eventRepository, dynamicScheduler
        )
        event = Event(
            id = 1L, name = "e",
            eventTime = LocalDateTime.now().plusDays(1),
            status = EventStatus.OPEN,
            ticketCloseTime = LocalDateTime.now().plusHours(1)
        )
    }

    private fun pendingSeat(): Seat = Seat(
        id = 10L, event = event, seatNumber = "A-1", section = "A",
        status = SeatStatus.PAYMENT_PENDING, userId = 1L, priceAmount = 200000L
    )

    @Test
    @DisplayName("결제 성공 시 Seat 상태가 RESERVED로 전이된다")
    fun paySuccessReserves() {
        val seat = pendingSeat()
        `when`(seatRepository.findByEventIdAndUserIdAndStatus(1L, 1L, SeatStatus.PAYMENT_PENDING))
            .thenReturn(seat)
        `when`(paymentClient.processPayment(
            PaymentProcessRequest(userId = 1L, seatId = 10L, eventId = 1L, amount = 200000L, method = "CARD")
        ))
            .thenReturn(PaymentProcessResult(success = true, paymentId = 100L, message = "ok"))

        val result = orchestrator.pay(1L, 1L, "CARD")

        assertTrue(result.success)
        assertEquals(SeatStatus.RESERVED, seat.status)
        assertEquals(100L, result.paymentId)
        verify(eventCache, never()).adjustSeatCounts(anyLong(), anyLong(), anyString())
        verify(eventCache, never()).markSeatAvailable(anyLong(), anyLong())
    }

    @Test
    @DisplayName("결제 실패 시 Seat이 AVAILABLE로 복구되고 스케줄러 재시작된다")
    fun payFailureCompensates() {
        val seat = pendingSeat()
        `when`(seatRepository.findByEventIdAndUserIdAndStatus(1L, 1L, SeatStatus.PAYMENT_PENDING))
            .thenReturn(seat)
        `when`(paymentClient.processPayment(
            PaymentProcessRequest(userId = 1L, seatId = 10L, eventId = 1L, amount = 200000L, method = "CARD")
        ))
            .thenReturn(PaymentProcessResult(success = false, message = "failed"))
        `when`(eventRepository.findById(1L)).thenReturn(Optional.of(event))

        val result = orchestrator.pay(1L, 1L, "CARD")

        assertFalse(result.success)
        assertEquals(SeatStatus.AVAILABLE, seat.status)
        assertNull(seat.userId)
        verify(eventCache).adjustSeatCounts(1L, 1, "A")
        verify(eventCache).markSeatAvailable(1L, 10L)
        verify(dynamicScheduler).startProcessing(1L)
    }

    @Test
    @DisplayName("결제 대기 좌석이 없으면 PAYMENT_PENDING_NOT_FOUND 예외")
    fun noPendingSeatThrows() {
        `when`(seatRepository.findByEventIdAndUserIdAndStatus(1L, 1L, SeatStatus.PAYMENT_PENDING))
            .thenReturn(null)

        val exception = assertThrows(ServerException::class.java) {
            orchestrator.pay(1L, 1L, "CARD")
        }
        assertEquals("PAYMENT_PENDING_NOT_FOUND", exception.code)
    }

    @Test
    @DisplayName("결제 실패했는데 이벤트가 이미 종료됐으면 스케줄러 재시작하지 않는다")
    fun payFailureAfterEventClosedDoesNotRestartScheduler() {
        val seat = pendingSeat()
        event.status = EventStatus.CLOSED
        `when`(seatRepository.findByEventIdAndUserIdAndStatus(1L, 1L, SeatStatus.PAYMENT_PENDING))
            .thenReturn(seat)
        `when`(paymentClient.processPayment(
            PaymentProcessRequest(userId = 1L, seatId = 10L, eventId = 1L, amount = 200000L, method = "CARD")
        ))
            .thenReturn(PaymentProcessResult(success = false, message = "failed"))
        `when`(eventRepository.findById(1L)).thenReturn(Optional.of(event))

        orchestrator.pay(1L, 1L, "CARD")

        verify(dynamicScheduler, never()).startProcessing(anyLong())
    }
}

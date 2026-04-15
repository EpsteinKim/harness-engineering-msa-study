package com.epstein.practice.reserveservice.service

import com.epstein.practice.common.exception.ServerException
import com.epstein.practice.reserveservice.cache.EventCacheRepository
import com.epstein.practice.reserveservice.client.PaymentClient
import com.epstein.practice.reserveservice.client.PaymentSummary
import com.epstein.practice.reserveservice.entity.Event
import com.epstein.practice.reserveservice.entity.EventStatus
import com.epstein.practice.reserveservice.entity.Seat
import com.epstein.practice.reserveservice.entity.SeatStatus
import com.epstein.practice.reserveservice.repository.EventRepository
import com.epstein.practice.reserveservice.repository.SeatRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.time.LocalDateTime
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class EventServiceTest {

    @Mock lateinit var eventRepository: EventRepository
    @Mock lateinit var seatRepository: SeatRepository
    @Mock lateinit var eventCache: EventCacheRepository
    @Mock lateinit var paymentClient: PaymentClient

    private lateinit var service: EventService

    private val event = Event(
        id = 1L, name = "Concert",
        eventTime = LocalDateTime.of(2026, 5, 1, 19, 0),
        status = EventStatus.OPEN,
        ticketOpenTime = LocalDateTime.of(2026, 4, 1, 10, 0),
        ticketCloseTime = LocalDateTime.of(2026, 4, 30, 23, 59)
    )

    @BeforeEach
    fun setUp() {
        service = EventService(eventRepository, seatRepository, eventCache, paymentClient)
    }

    @Test
    @DisplayName("listEvents(OPEN) - Redis ZSET 인덱스 hit 시 DB 조회 없이 캐시로 응답")
    fun listEventsRedisFirst() {
        `when`(eventCache.getOpenEventIdsOrderedByTicketOpenTime()).thenReturn(listOf(1L))
        `when`(eventCache.getAllFields(1L)).thenReturn(mapOf(
            "id" to "1",
            "name" to "Concert",
            "remainingSeats" to "300",
            "status" to "OPEN",
            "eventTime" to "2026-05-01T19:00",
            "ticketOpenTime" to "2026-04-01T10:00",
            "ticketCloseTime" to "2026-04-30T23:59",
            "seatSelectionType" to "SEAT_PICK"
        ))

        val result = service.listEvents(EventStatus.OPEN)

        assertEquals(1, result.size)
        assertEquals("Concert", result[0].name)
        assertEquals(300L, result[0].remainingSeats)
        verifyNoInteractions(eventRepository)
    }

    @Test
    @DisplayName("listEvents(OPEN) - Redis 인덱스 비어있으면 DB fallback")
    fun listEventsFallbackToDb() {
        `when`(eventCache.getOpenEventIdsOrderedByTicketOpenTime()).thenReturn(emptyList())
        `when`(eventRepository.findByStatusOrderByTicketOpenTimeAsc(EventStatus.OPEN)).thenReturn(listOf(event))
        `when`(eventCache.getRemainingSeats(1L)).thenReturn(300L)

        val result = service.listEvents(EventStatus.OPEN)

        assertEquals(1, result.size)
        assertEquals(300L, result[0].remainingSeats)
    }

    @Test
    @DisplayName("listEvents(CLOSED) - OPEN이 아니면 항상 DB 조회")
    fun listEventsClosedAlwaysDb() {
        `when`(eventRepository.findByStatusOrderByTicketOpenTimeAsc(EventStatus.CLOSED)).thenReturn(emptyList())

        service.listEvents(EventStatus.CLOSED)

        verify(eventCache, never()).getOpenEventIdsOrderedByTicketOpenTime()
    }

    @Test
    @DisplayName("getEvent - 캐시 hit 시 DB 조회 없이 응답")
    fun getEventCacheHit() {
        `when`(eventCache.getAllFields(1L)).thenReturn(mapOf(
            "id" to "1",
            "name" to "Concert",
            "remainingSeats" to "200",
            "status" to "OPEN",
            "eventTime" to "2026-05-01T19:00",
            "seatSelectionType" to "SEAT_PICK"
        ))

        val result = service.getEvent(1L)

        assertEquals(1L, result.id)
        assertEquals(200L, result.remainingSeats)
        verifyNoInteractions(eventRepository)
    }

    @Test
    @DisplayName("getEvent - 캐시 miss 시 DB fallback")
    fun getEventCacheMissDbFallback() {
        `when`(eventCache.getAllFields(1L)).thenReturn(emptyMap())
        `when`(eventRepository.findById(1L)).thenReturn(Optional.of(event))
        `when`(eventCache.getRemainingSeats(1L)).thenReturn(200L)

        val result = service.getEvent(1L)

        assertEquals(1L, result.id)
        assertEquals(200L, result.remainingSeats)
    }

    @Test
    @DisplayName("getEvent - 캐시와 DB 모두 없으면 ServerException")
    fun getEventNotFound() {
        `when`(eventCache.getAllFields(999L)).thenReturn(emptyMap())
        `when`(eventRepository.findById(999L)).thenReturn(Optional.empty())
        assertThrows(ServerException::class.java) { service.getEvent(999L) }
    }

    @Test
    @DisplayName("getMyReservations - 좌석과 결제 정보 조합해 반환")
    fun getMyReservationsWithPayment() {
        val seat = Seat(
            id = 10L, event = event, seatNumber = "A-1", section = "A",
            status = SeatStatus.RESERVED, userId = 1L, priceAmount = 200000L,
            reservedAt = LocalDateTime.of(2026, 4, 10, 9, 0)
        )
        `when`(seatRepository.findActiveByUserId(1L)).thenReturn(listOf(seat))
        `when`(paymentClient.listByUser(1L)).thenReturn(
            listOf(PaymentSummary(id = 100L, seatId = 10L, status = "SUCCEEDED"))
        )

        val result = service.getMyReservations(1L)

        assertEquals(1, result.size)
        assertEquals(10L, result[0].seatId)
        assertEquals(100L, result[0].paymentId)
        assertEquals("SUCCEEDED", result[0].paymentStatus)
        assertEquals(200000L, result[0].priceAmount)
    }

    @Test
    @DisplayName("getMyReservations - 좌석 없으면 payment-service 호출 없이 빈 리스트")
    fun getMyReservationsNoSeats() {
        `when`(seatRepository.findActiveByUserId(1L)).thenReturn(emptyList())

        val result = service.getMyReservations(1L)

        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("getMyReservations - 결제 정보 없으면 paymentId/paymentStatus null")
    fun getMyReservationsNoPayment() {
        val seat = Seat(
            id = 10L, event = event, seatNumber = "A-1", section = "A",
            status = SeatStatus.PAYMENT_PENDING, userId = 1L, priceAmount = 200000L
        )
        `when`(seatRepository.findActiveByUserId(1L)).thenReturn(listOf(seat))
        `when`(paymentClient.listByUser(1L)).thenReturn(emptyList())

        val result = service.getMyReservations(1L)

        assertEquals(1, result.size)
        assertNull(result[0].paymentId)
        assertNull(result[0].paymentStatus)
    }
}

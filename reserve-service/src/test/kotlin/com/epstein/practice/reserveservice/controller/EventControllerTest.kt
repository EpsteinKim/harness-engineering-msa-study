package com.epstein.practice.reserveservice.controller

import com.epstein.practice.common.exception.GlobalExceptionHandler
import com.epstein.practice.reserveservice.dto.EventSummaryResponse
import com.epstein.practice.reserveservice.dto.MyReservationItem
import com.epstein.practice.reserveservice.entity.EventStatus
import com.epstein.practice.reserveservice.service.EventService
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
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
class EventControllerTest {

    @Mock
    lateinit var eventService: EventService

    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(EventController(eventService))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    private fun summary() = EventSummaryResponse(
        id = 1L, name = "Concert",
        eventTime = LocalDateTime.of(2026, 5, 1, 19, 0),
        status = "OPEN",
        ticketOpenTime = null, ticketCloseTime = null,
        seatSelectionType = "SEAT_PICK",
        remainingSeats = 300L
    )

    @Test
    @DisplayName("GET /events - 기본 OPEN 상태로 호출")
    fun listEventsDefault() {
        `when`(eventService.listEvents(EventStatus.OPEN)).thenReturn(listOf(summary()))

        mockMvc.perform(get("/api/v1/reservations/events"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(1))
            .andExpect(jsonPath("$.data[0].remainingSeats").value(300))
    }

    @Test
    @DisplayName("GET /events?status=CLOSED - status 파라미터 변환")
    fun listEventsClosed() {
        `when`(eventService.listEvents(EventStatus.CLOSED)).thenReturn(emptyList())

        mockMvc.perform(get("/api/v1/reservations/events").param("status", "CLOSED"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(0))
    }

    @Test
    @DisplayName("GET /events?status=BOGUS - INVALID_REQUEST")
    fun listEventsInvalidStatus() {
        mockMvc.perform(get("/api/v1/reservations/events").param("status", "BOGUS"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
    }

    @Test
    @DisplayName("GET /events/{id} - 단건")
    fun getEvent() {
        `when`(eventService.getEvent(1L)).thenReturn(summary())

        mockMvc.perform(get("/api/v1/reservations/events/1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(1))
            .andExpect(jsonPath("$.data.name").value("Concert"))
    }

    @Test
    @DisplayName("GET /my - 사용자 예약 이력")
    fun getMyReservations() {
        `when`(eventService.getMyReservations(1L)).thenReturn(
            listOf(
                MyReservationItem(
                    eventId = 1L, eventName = "Concert",
                    eventTime = LocalDateTime.of(2026, 5, 1, 19, 0),
                    seatId = 10L, seatNumber = "A-1", section = "A",
                    priceAmount = 200000L,
                    paymentId = 100L, paymentStatus = "SUCCEEDED",
                    reservedAt = LocalDateTime.of(2026, 4, 10, 9, 0)
                )
            )
        )

        mockMvc.perform(get("/api/v1/reservations/my").param("userId", "1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].seatId").value(10))
            .andExpect(jsonPath("$.data[0].paymentStatus").value("SUCCEEDED"))
    }
}

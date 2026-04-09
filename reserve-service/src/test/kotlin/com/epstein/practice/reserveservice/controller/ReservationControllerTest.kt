package com.epstein.practice.reserveservice.controller

import com.epstein.practice.reserveservice.dto.SeatDTO
import com.epstein.practice.reserveservice.dto.SectionAvailabilityResponse
import com.epstein.practice.reserveservice.entity.SeatStatus
import com.epstein.practice.reserveservice.service.ReservationService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders

@ExtendWith(MockitoExtension::class)
class ReservationControllerTest {

    @Mock
    lateinit var queueService: ReservationService

    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(ReservationController(queueService))
            .build()
    }

    @Nested
    @DisplayName("POST /api/v1/reservations - 예약 요청")
    inner class Enqueue {

        @Test
        @DisplayName("좌석 ID로 예약 요청하면 대기열에 추가된다")
        fun enqueueBySeatId() {
            `when`(queueService.getPosition("user-1")).thenReturn(0L)

            mockMvc.perform(
                post("/api/v1/reservations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userId":"user-1","eventId":1,"seatId":10}""")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.userId").value("user-1"))
                .andExpect(jsonPath("$.data.position").value(0))

            verify(queueService).enqueue("user-1", 1L, seatId = 10L)
        }

        @Test
        @DisplayName("이벤트가 열리지 않았으면 EVENT_NOT_OPEN 에러를 반환한다")
        fun enqueueEventNotOpen() {
            `when`(queueService.enqueue(anyString(), anyLong(), anyLong(), eq(null)))
                .thenThrow(IllegalStateException("Event is not open for reservations"))

            mockMvc.perform(
                post("/api/v1/reservations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userId":"user-1","eventId":1,"seatId":10}""")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value("EVENT_NOT_OPEN"))
        }

        @Test
        @DisplayName("잔여석이 없으면 에러를 반환한다")
        fun enqueueNoRemainingSeats() {
            `when`(queueService.enqueue(anyString(), anyLong(), anyLong(), eq(null)))
                .thenThrow(IllegalStateException("No remaining seats"))

            mockMvc.perform(
                post("/api/v1/reservations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userId":"user-1","eventId":1,"seatId":10}""")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("No remaining seats"))
        }

        @Test
        @DisplayName("SEAT_PICK 이벤트에 seatId 없이 요청하면 INVALID_REQUEST 에러를 반환한다")
        fun enqueueSeatPickWithoutSeatIdReturnsError() {
            `when`(queueService.enqueue(anyString(), anyLong(), eq(null), anyString()))
                .thenThrow(IllegalArgumentException("SEAT_PICK events require a specific seatId"))

            mockMvc.perform(
                post("/api/v1/reservations/section")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userId":"user-1","eventId":1,"section":"A"}""")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }

        @Test
        @DisplayName("이미 대기열에 있는 유저는 EVENT_NOT_OPEN 에러를 반환한다")
        fun enqueueAlreadyInQueue() {
            `when`(queueService.enqueue(anyString(), anyLong(), anyLong(), eq(null)))
                .thenThrow(IllegalStateException("Already in queue"))

            mockMvc.perform(
                post("/api/v1/reservations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userId":"user-1","eventId":1,"seatId":10}""")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value("EVENT_NOT_OPEN"))
        }

        @Test
        @DisplayName("seatId, section 모두 없으면 INVALID_REQUEST 에러를 반환한다")
        fun enqueueWithoutSeatIdOrSection() {
            mockMvc.perform(
                post("/api/v1/reservations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userId":"user-1","eventId":1}""")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }
    }

    @Nested
    @DisplayName("GET /api/v1/reservations/seats/{eventId}/sections - 구역별 잔여석")
    inner class GetSectionAvailability {

        @Test
        @DisplayName("구역별 잔여석 정보를 반환한다")
        fun getSectionAvailability() {
            val availability = listOf(
                SectionAvailabilityResponse("A", 5L, 10L),
                SectionAvailabilityResponse("B", 3L, 10L)
            )
            `when`(queueService.getSectionAvailability(1L)).thenReturn(availability)

            mockMvc.perform(get("/api/v1/reservations/seats/1/sections"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].section").value("A"))
                .andExpect(jsonPath("$.data[0].availableCount").value(5))
                .andExpect(jsonPath("$.data[0].totalCount").value(10))
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/reservations/queue/{userId} - 예약 취소")
    inner class Cancel {

        @Test
        @DisplayName("취소 성공 시 success를 반환한다")
        fun cancelSuccess() {
            `when`(queueService.cancel("user-1")).thenReturn(true)

            mockMvc.perform(delete("/api/v1/reservations/queue/user-1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data").value("user-1"))
                .andExpect(jsonPath("$.message").value("Cancelled"))
        }

        @Test
        @DisplayName("대기열에 없는 유저면 error를 반환한다")
        fun cancelNotFound() {
            `when`(queueService.cancel("user-1")).thenReturn(false)

            mockMvc.perform(delete("/api/v1/reservations/queue/user-1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value("QUEUE_NOT_FOUND"))
        }
    }

    @Nested
    @DisplayName("POST /api/v1/reservations/section - 구역별 예약 요청")
    inner class EnqueueBySection {

        @Test
        @DisplayName("유효한 구역으로 예약 요청하면 대기열에 추가된다")
        fun enqueueValidSection() {
            `when`(queueService.getPosition("user-1")).thenReturn(0L)

            mockMvc.perform(
                post("/api/v1/reservations/section")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userId":"user-1","eventId":1,"section":"A"}""")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.userId").value("user-1"))
                .andExpect(jsonPath("$.message").value("Section reservation request queued"))

            verify(queueService).enqueue("user-1", 1L, section = "A")
        }

        @Test
        @DisplayName("이벤트가 열리지 않았으면 EVENT_NOT_OPEN 에러를 반환한다")
        fun enqueueBySectionEventNotOpen() {
            `when`(queueService.enqueue(anyString(), anyLong(), eq(null), anyString()))
                .thenThrow(IllegalStateException("Event is not open for reservations"))

            mockMvc.perform(
                post("/api/v1/reservations/section")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userId":"user-1","eventId":1,"section":"A"}""")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value("EVENT_NOT_OPEN"))
        }

        @Test
        @DisplayName("유효하지 않은 구역 - 소문자")
        fun invalidSectionLowercase() {
            mockMvc.perform(
                post("/api/v1/reservations/section")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userId":"user-1","eventId":1,"section":"a"}""")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value("INVALID_SECTION"))
        }

        @Test
        @DisplayName("유효하지 않은 구역 - 2글자 이상")
        fun invalidSectionMultiChar() {
            mockMvc.perform(
                post("/api/v1/reservations/section")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userId":"user-1","eventId":1,"section":"AB"}""")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value("INVALID_SECTION"))
        }
    }
}

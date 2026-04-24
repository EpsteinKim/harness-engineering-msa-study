package com.epstein.practice.reserveservice.main.controller

import com.epstein.practice.common.exception.GlobalExceptionHandler
import com.epstein.practice.common.exception.ServerException
import com.epstein.practice.reserveservice.type.dto.MyReservationItem
import com.epstein.practice.reserveservice.main.service.ReservationService
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
import java.time.ZonedDateTime

@ExtendWith(MockitoExtension::class)
class ReservationControllerTest {

    @Mock
    lateinit var queueService: ReservationService

    @Mock
    lateinit var paymentInitiator: com.epstein.practice.reserveservice.producer.PaymentInitiator

    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(ReservationController(queueService, paymentInitiator))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    @Nested
    @DisplayName("POST /api/v1/reservations - 예약 요청")
    inner class Enqueue {

        @Test
        @DisplayName("좌석 ID로 예약 요청하면 대기열에 추가된다")
        fun enqueueBySeatId() {
            `when`(queueService.getPosition(1L, "1")).thenReturn(0L)

            mockMvc.perform(
                post("/api/v1/reservations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userId":"1","eventId":1,"seatId":10}""")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.userId").value("1"))
                .andExpect(jsonPath("$.data.position").value(0))

            verify(queueService).enqueue("1", 1L, seatId = 10L)
        }

        @Test
        @DisplayName("구역으로 예약 요청하면 대기열에 추가된다")
        fun enqueueBySection() {
            `when`(queueService.getPosition(1L, "1")).thenReturn(0L)

            mockMvc.perform(
                post("/api/v1/reservations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userId":"1","eventId":1,"section":"A"}""")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.userId").value("1"))

            verify(queueService).enqueue("1", 1L, section = "A")
        }

        @Test
        @DisplayName("이벤트가 열리지 않았으면 EVENT_NOT_OPEN 에러를 반환한다")
        fun enqueueEventNotOpen() {
            `when`(queueService.enqueue(anyString(), anyLong(), anyLong(), eq(null)))
                .thenThrow(ServerException(message = "이벤트가 예약 가능한 상태가 아닙니다", code = "EVENT_NOT_OPEN"))

            mockMvc.perform(
                post("/api/v1/reservations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userId":"1","eventId":1,"seatId":10}""")
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value("EVENT_NOT_OPEN"))
        }

        @Test
        @DisplayName("잔여석이 없으면 에러를 반환한다")
        fun enqueueNoRemainingSeats() {
            `when`(queueService.enqueue(anyString(), anyLong(), anyLong(), eq(null)))
                .thenThrow(ServerException(message = "잔여 좌석이 없습니다", code = "NO_REMAINING_SEATS"))

            mockMvc.perform(
                post("/api/v1/reservations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userId":"1","eventId":1,"seatId":10}""")
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value("NO_REMAINING_SEATS"))
        }

        @Test
        @DisplayName("이미 대기열에 있는 유저는 ALREADY_IN_QUEUE 에러를 반환한다")
        fun enqueueAlreadyInQueue() {
            `when`(queueService.enqueue(anyString(), anyLong(), anyLong(), eq(null)))
                .thenThrow(ServerException(message = "이미 대기열에 등록되어 있습니다", code = "ALREADY_IN_QUEUE"))

            mockMvc.perform(
                post("/api/v1/reservations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userId":"1","eventId":1,"seatId":10}""")
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value("ALREADY_IN_QUEUE"))
        }

        @Test
        @DisplayName("seatId, section 모두 없으면 INVALID_REQUEST 에러를 반환한다")
        fun enqueueWithoutSeatIdOrSection() {
            mockMvc.perform(
                post("/api/v1/reservations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userId":"1","eventId":1}""")
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }

        @Test
        @DisplayName("유효하지 않은 구역 - 소문자")
        fun invalidSectionLowercase() {
            mockMvc.perform(
                post("/api/v1/reservations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userId":"1","eventId":1,"section":"a"}""")
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value("INVALID_SECTION"))
        }

        @Test
        @DisplayName("유효하지 않은 구역 - 2글자 이상")
        fun invalidSectionMultiChar() {
            mockMvc.perform(
                post("/api/v1/reservations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userId":"1","eventId":1,"section":"AB"}""")
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value("INVALID_SECTION"))
        }
    }

    @Nested
    @DisplayName("GET /api/v1/reservations/my - 사용자 예약 이력")
    inner class GetMyReservations {

        @Test
        @DisplayName("예약 이력을 반환한다")
        fun getMyReservations() {
            `when`(queueService.getMyReservations(1L)).thenReturn(
                listOf(
                    MyReservationItem(
                        eventId = 1L, eventName = "Concert",
                        eventTime = ZonedDateTime.of(2026, 5, 1, 19, 0, 0, 0, java.time.ZoneOffset.UTC),
                        seatId = 10L, seatNumber = "A-1", section = "A",
                        seatStatus = "RESERVED",
                        priceAmount = 200000L,
                        paymentId = 100L, paymentStatus = "SUCCEEDED",
                        reservedAt = ZonedDateTime.of(2026, 4, 10, 9, 0, 0, 0, java.time.ZoneOffset.UTC)
                    )
                )
            )

            mockMvc.perform(get("/api/v1/reservations/my").param("userId", "1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].seatId").value(10))
                .andExpect(jsonPath("$.data[0].seatStatus").value("RESERVED"))
                .andExpect(jsonPath("$.data[0].paymentStatus").value("SUCCEEDED"))
        }
    }

    @Nested
    @DisplayName("GET /api/v1/reservations/queue/{eventId}/{userId} - 대기열 순번 조회")
    inner class GetQueuePosition {

        @Test
        @DisplayName("대기 중이면 position과 inQueue=true를 반환한다")
        fun inQueue() {
            `when`(queueService.getPosition(1L, "1")).thenReturn(3L)

            mockMvc.perform(get("/api/v1/reservations/queue/1/1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.position").value(3))
                .andExpect(jsonPath("$.data.inQueue").value(true))
        }

        @Test
        @DisplayName("대기열에 없으면 position=null, inQueue=false")
        fun notInQueue() {
            `when`(queueService.getPosition(1L, "1")).thenReturn(null)

            mockMvc.perform(get("/api/v1/reservations/queue/1/1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.position").isEmpty)
                .andExpect(jsonPath("$.data.inQueue").value(false))
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/reservations/queue/{eventId}/{userId} - 예약 취소")
    inner class Cancel {

        @Test
        @DisplayName("취소 성공 시 success를 반환한다")
        fun cancelSuccess() {
            `when`(queueService.cancel(1L, "1")).thenReturn(true)

            mockMvc.perform(delete("/api/v1/reservations/queue/1/1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data").value("1"))
                .andExpect(jsonPath("$.message").value("취소되었습니다"))
        }

        @Test
        @DisplayName("대기열에 없는 유저면 error를 반환한다")
        fun cancelNotFound() {
            `when`(queueService.cancel(1L, "1")).thenReturn(false)

            mockMvc.perform(delete("/api/v1/reservations/queue/1/1"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value("QUEUE_NOT_FOUND"))
        }
    }
}

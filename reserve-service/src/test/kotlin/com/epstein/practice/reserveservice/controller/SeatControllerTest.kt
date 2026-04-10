package com.epstein.practice.reserveservice.controller

import com.epstein.practice.reserveservice.dto.SectionAvailabilityResponse
import com.epstein.practice.reserveservice.service.SeatService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders

@ExtendWith(MockitoExtension::class)
class SeatControllerTest {

    @Mock
    lateinit var seatService: SeatService

    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(SeatController(seatService))
            .build()
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
            `when`(seatService.getSectionAvailability(1L)).thenReturn(availability)

            mockMvc.perform(get("/api/v1/reservations/seats/1/sections"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].section").value("A"))
                .andExpect(jsonPath("$.data[0].availableCount").value(5))
                .andExpect(jsonPath("$.data[0].totalCount").value(10))
        }

        @Test
        @DisplayName("캐시가 없으면 빈 리스트를 반환한다")
        fun getSectionAvailabilityEmpty() {
            `when`(seatService.getSectionAvailability(999L)).thenReturn(emptyList())

            mockMvc.perform(get("/api/v1/reservations/seats/999/sections"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.length()").value(0))
        }
    }
}

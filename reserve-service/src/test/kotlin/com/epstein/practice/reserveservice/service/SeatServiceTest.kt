package com.epstein.practice.reserveservice.service

import com.epstein.practice.reserveservice.cache.EventCacheRepository
import com.epstein.practice.reserveservice.entity.Event
import com.epstein.practice.reserveservice.entity.Seat
import com.epstein.practice.reserveservice.entity.SeatStatus
import com.epstein.practice.reserveservice.repository.SeatRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
class SeatServiceTest {

    @Mock
    lateinit var seatRepository: SeatRepository

    @Mock
    lateinit var eventCache: EventCacheRepository

    private lateinit var seatService: SeatService

    private lateinit var event: Event
    private lateinit var availableSeat: Seat

    @BeforeEach
    fun setUp() {
        seatService = SeatService(seatRepository, eventCache)
        event = Event(id = 1L, name = "Concert", eventTime = LocalDateTime.of(2026, 5, 1, 19, 0))
        availableSeat = Seat(
            id = 10L,
            event = event,
            seatNumber = "A-1",
            section = "A",
            status = SeatStatus.AVAILABLE
        )
    }

    @Nested
    @DisplayName("reserveSeat - 특정 좌석 예약")
    inner class ReserveSeat {

        @Test
        @DisplayName("성공 - 빈 좌석을 예약하면 성공 결과를 반환한다")
        fun reserveSeatSuccess() {
            `when`(seatRepository.findByEventIdAndId(1L, 10L)).thenReturn(availableSeat)
            `when`(seatRepository.save(any(Seat::class.java))).thenReturn(availableSeat)

            val result = seatService.reserveBySeatId(1L, 10L, 1L)

            assertTrue(result.success)
            assertEquals("reservation successful", result.message)
            assertEquals(1L, result.userId)
            assertEquals(10L, result.seatId)
            assertEquals("A", result.section)
            assertEquals(SeatStatus.RESERVED, availableSeat.status)
            assertEquals(1L, availableSeat.userId)
        }

        @Test
        @DisplayName("실패 - 존재하지 않는 좌석이면 실패를 반환한다")
        fun reserveSeatNotFound() {
            `when`(seatRepository.findByEventIdAndId(1L, 999L)).thenReturn(null)

            val result = seatService.reserveBySeatId(1L, 999L, 1L)

            assertFalse(result.success)
            assertEquals("seat not found", result.message)
        }

        @Test
        @DisplayName("실패 - 이미 예약된 좌석이면 실패를 반환한다")
        fun reserveSeatAlreadyReserved() {
            val reservedSeat = Seat(
                id = 10L, event = event, seatNumber = "A-1", section = "A",
                status = SeatStatus.RESERVED, userId = 2L
            )
            `when`(seatRepository.findByEventIdAndId(1L, 10L)).thenReturn(reservedSeat)

            val result = seatService.reserveBySeatId(1L, 10L, 1L)

            assertFalse(result.success)
            assertEquals("seat already reserved", result.message)
        }

    }

    @Nested
    @DisplayName("reserveSeatBySection - 구역 자동 배정")
    inner class ReserveSeatBySection {

        @Test
        @DisplayName("성공 - 구역에 빈 좌석이 있으면 자동 배정한다")
        fun reserveBySectionSuccess() {
            `when`(seatRepository.findFirstAvailableSeatForUpdate(1L, "A")).thenReturn(availableSeat)
            `when`(seatRepository.save(any(Seat::class.java))).thenReturn(availableSeat)

            val result = seatService.reserveBySection(1L, "A", 1L)

            assertTrue(result.success)
            assertEquals("seat A-1 reserved", result.message)
            assertEquals(10L, result.seatId)
            assertEquals("A", result.section)
            assertEquals(SeatStatus.RESERVED, availableSeat.status)
            assertEquals(1L, availableSeat.userId)
        }

        @Test
        @DisplayName("실패 - 구역에 빈 좌석이 없으면 실패를 반환한다")
        fun reserveBySectionNoAvailable() {
            `when`(seatRepository.findFirstAvailableSeatForUpdate(1L, "B")).thenReturn(null)

            val result = seatService.reserveBySection(1L, "B", 1L)

            assertFalse(result.success)
            assertEquals("no available seat in section B", result.message)
        }
    }

    @Nested
    @DisplayName("releaseSeat - 좌석 해제")
    inner class ReleaseSeat {

        @Test
        @DisplayName("성공 - 예약된 좌석을 해제한다")
        fun releaseSeatSuccess() {
            val reservedSeat = Seat(
                id = 10L, event = event, seatNumber = "A-1", section = "A",
                status = SeatStatus.RESERVED, userId = 1L
            )
            `when`(seatRepository.findByEventIdAndUserId(1L, 1L)).thenReturn(reservedSeat)
            `when`(seatRepository.save(any(Seat::class.java))).thenReturn(reservedSeat)

            val result = seatService.releaseSeat(1L, 1L)

            assertTrue(result.success)
            assertEquals("A", result.section)
            assertEquals(SeatStatus.AVAILABLE, reservedSeat.status)
            assertNull(reservedSeat.userId)
        }

        @Test
        @DisplayName("실패 - 예약된 좌석이 없으면 실패를 반환한다")
        fun releaseSeatNotFound() {
            `when`(seatRepository.findByEventIdAndUserId(1L, 1L)).thenReturn(null)

            val result = seatService.releaseSeat(1L, 1L)

            assertFalse(result.success)
        }
    }

    @Nested
    @DisplayName("getSectionAvailability - 구역별 잔여석 조회")
    inner class GetSectionAvailability {

        @Test
        @DisplayName("캐시에서 구역별 잔여석을 반환한다")
        fun getSectionAvailabilityFromCache() {
            `when`(eventCache.getAllFields(1L)).thenReturn(mapOf(
                "name" to "Concert",
                "remainingSeats" to "50",
                "section:A:available" to "25",
                "section:A:total" to "30",
                "section:B:available" to "20",
                "section:B:total" to "30"
            ))

            val result = seatService.getSectionAvailability(1L)

            assertEquals(2, result.size)
            assertEquals("A", result[0].section)
            assertEquals(25L, result[0].availableCount)
            assertEquals(30L, result[0].totalCount)
        }

        @Test
        @DisplayName("캐시가 없으면 빈 리스트를 반환한다")
        fun getSectionAvailabilityEmpty() {
            `when`(eventCache.getAllFields(1L)).thenReturn(emptyMap())
            assertTrue(seatService.getSectionAvailability(1L).isEmpty())
        }
    }

    @Nested
    @DisplayName("getSeatMap - 좌석 맵 조회")
    inner class GetSeatMap {

        @Test
        @DisplayName("만료된 HELD는 AVAILABLE로 응답한다")
        fun expiredHeldBecomesAvailable() {
            val pastMs = System.currentTimeMillis() - 10_000L
            `when`(eventCache.getAllSeatFields(1L)).thenReturn(
                mapOf("10" to "A:A-1:HELD:user1:$pastMs")
            )

            val result = seatService.getSeatMap(1L, null)

            assertEquals(1, result.size)
            assertEquals("AVAILABLE", result[0].status)
            assertEquals(10L, result[0].seatId)
            assertEquals("A", result[0].section)
            assertEquals("A-1", result[0].seatNumber)
        }

        @Test
        @DisplayName("만료되지 않은 HELD는 HELD로 유지한다")
        fun activeHeldStaysHeld() {
            val futureMs = System.currentTimeMillis() + 60_000L
            `when`(eventCache.getAllSeatFields(1L)).thenReturn(
                mapOf("10" to "A:A-1:HELD:user1:$futureMs")
            )

            val result = seatService.getSeatMap(1L, null)

            assertEquals(1, result.size)
            assertEquals("HELD", result[0].status)
        }

        @Test
        @DisplayName("응답 DTO는 seatId/section/seatNumber/status만 노출한다")
        fun responseExposesOnlySafeFields() {
            val futureMs = System.currentTimeMillis() + 60_000L
            `when`(eventCache.getAllSeatFields(1L)).thenReturn(
                mapOf("10" to "A:A-1:HELD:user1:$futureMs")
            )

            val entry = seatService.getSeatMap(1L, null)[0]
            val fieldNames = entry.javaClass.declaredFields.map { it.name }.toSet()

            assertEquals(setOf("seatId", "section", "seatNumber", "status"), fieldNames)
        }

        @Test
        @DisplayName("section 필터 적용 - 지정한 섹션만 반환한다")
        fun sectionFilterApplied() {
            `when`(eventCache.getAllSeatFields(1L)).thenReturn(
                mapOf(
                    "10" to "A:A-1:AVAILABLE",
                    "11" to "A:A-2:RESERVED",
                    "20" to "B:B-1:AVAILABLE"
                )
            )

            val result = seatService.getSeatMap(1L, "A")

            assertEquals(2, result.size)
            assertTrue(result.all { it.section == "A" })
        }

        @Test
        @DisplayName("캐시가 비어있으면 빈 리스트를 반환한다")
        fun emptyCacheReturnsEmptyList() {
            `when`(eventCache.getAllSeatFields(1L)).thenReturn(emptyMap())
            assertTrue(seatService.getSeatMap(1L, null).isEmpty())
        }
    }
}

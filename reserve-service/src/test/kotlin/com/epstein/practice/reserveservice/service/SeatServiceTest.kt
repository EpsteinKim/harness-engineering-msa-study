package com.epstein.practice.reserveservice.service

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
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.orm.ObjectOptimisticLockingFailureException
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
class SeatServiceTest {

    @Mock
    lateinit var seatRepository: SeatRepository

    @InjectMocks
    lateinit var seatService: SeatService

    private lateinit var event: Event
    private lateinit var availableSeat: Seat

    @BeforeEach
    fun setUp() {
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

            val result = seatService.reserveBySeatId(1L, 10L, "user-1")

            assertTrue(result.success)
            assertEquals("Reservation successful", result.message)
            assertEquals("user-1", result.userId)
            assertEquals(1L, result.eventId)
            assertEquals(10L, result.seatId)
            assertEquals("A", result.section)

            assertEquals(SeatStatus.RESERVED, availableSeat.status)
            assertEquals("user-1", availableSeat.reservedBy)
            assertNotNull(availableSeat.reservedAt)
            verify(seatRepository).save(availableSeat)
        }

        @Test
        @DisplayName("실패 - 존재하지 않는 좌석이면 실패를 반환한다")
        fun reserveSeatNotFound() {
            `when`(seatRepository.findByEventIdAndId(1L, 999L)).thenReturn(null)

            val result = seatService.reserveBySeatId(1L, 999L, "user-1")

            assertFalse(result.success)
            assertEquals("Seat not found", result.message)
            verify(seatRepository, never()).save(any(Seat::class.java))
        }

        @Test
        @DisplayName("실패 - 이미 예약된 좌석이면 실패를 반환한다")
        fun reserveSeatAlreadyReserved() {
            val reservedSeat = Seat(
                id = 10L, event = event, seatNumber = "A-1", section = "A",
                status = SeatStatus.RESERVED, reservedBy = "other-user"
            )
            `when`(seatRepository.findByEventIdAndId(1L, 10L)).thenReturn(reservedSeat)

            val result = seatService.reserveBySeatId(1L, 10L, "user-1")

            assertFalse(result.success)
            assertEquals("Seat already reserved", result.message)
            verify(seatRepository, never()).save(any(Seat::class.java))
        }

        @Test
        @DisplayName("실패 - 낙관적 락 충돌 시 실패를 반환한다")
        fun reserveSeatOptimisticLockConflict() {
            `when`(seatRepository.findByEventIdAndId(1L, 10L)).thenReturn(availableSeat)
            `when`(seatRepository.save(any(Seat::class.java)))
                .thenThrow(ObjectOptimisticLockingFailureException(Seat::class.java, 10L))

            val result = seatService.reserveBySeatId(1L, 10L, "user-1")

            assertFalse(result.success)
            assertEquals("Seat was taken by another user", result.message)
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

            val result = seatService.reserveBySection(1L, "A", "user-1")

            assertTrue(result.success)
            assertEquals("Seat A-1 reserved successfully", result.message)
            assertEquals(10L, result.seatId)
            assertEquals("A", result.section)
            assertEquals(SeatStatus.RESERVED, availableSeat.status)
            assertEquals("user-1", availableSeat.reservedBy)
        }

        @Test
        @DisplayName("실패 - 구역에 빈 좌석이 없으면 실패를 반환한다")
        fun reserveBySectionNoAvailable() {
            `when`(seatRepository.findFirstAvailableSeatForUpdate(1L, "B")).thenReturn(null)

            val result = seatService.reserveBySection(1L, "B", "user-1")

            assertFalse(result.success)
            assertEquals("No available seat in section B", result.message)
            assertEquals(0L, result.seatId)
            verify(seatRepository, never()).save(any(Seat::class.java))
        }
    }
}

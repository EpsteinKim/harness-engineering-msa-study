package com.epstein.practice.reserveservice.service

import com.epstein.practice.common.exception.ServerException
import com.epstein.practice.reserveservice.cache.EventCacheRepository
import com.epstein.practice.reserveservice.cache.QueueCacheRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class ReservationServiceTest {

    @Mock
    lateinit var eventCache: EventCacheRepository

    @Mock
    lateinit var queueCache: QueueCacheRepository

    @Mock
    lateinit var seatService: SeatService

    private lateinit var service: ReservationService

    @BeforeEach
    fun setUp() {
        service = ReservationService(eventCache, queueCache, seatService)
    }

    @Nested
    @DisplayName("enqueue - 대기열에 추가")
    inner class Enqueue {

        @Test
        @DisplayName("이벤트가 열려있으면 좌석 ID로 대기열에 추가한다")
        fun enqueueBySeatId() {
            `when`(eventCache.exists(1L)).thenReturn(true)
            `when`(eventCache.getRemainingSeats(1L)).thenReturn(100L)
            `when`(eventCache.getSeatSelectionType(1L)).thenReturn("SEAT_PICK")
            `when`(eventCache.getSeatStatus(1L, 10L)).thenReturn("A:A-1:AVAILABLE")
            `when`(queueCache.isInQueue(1L, "1")).thenReturn(false)

            service.enqueue("1", 1L, seatId = 10L)

            verify(queueCache).addToQueue(eq(1L) ?: 0, eq("1") ?: "", anyDouble())
            verify(queueCache).saveMetadata(eq(1L) ?: 0, eq("1") ?: "", argThat<Map<String, String>> { map ->
                map?.get("seatId") == "10"
            } ?: emptyMap())
        }

        @Test
        @DisplayName("이벤트가 열려있으면 구역으로 대기열에 추가한다")
        fun enqueueBySection() {
            `when`(eventCache.exists(1L)).thenReturn(true)
            `when`(eventCache.getRemainingSeats(1L)).thenReturn(100L)
            `when`(eventCache.getSeatSelectionType(1L)).thenReturn("SECTION_SELECT")
            `when`(queueCache.isInQueue(1L, "1")).thenReturn(false)

            service.enqueue("1", 1L, section = "A")

            verify(queueCache).addToQueue(eq(1L) ?: 0, eq("1") ?: "", anyDouble())
        }

        @Test
        @DisplayName("잔여석이 없으면 NO_REMAINING_SEATS 예외를 발생시킨다")
        fun enqueueNoRemainingSeats() {
            `when`(eventCache.exists(1L)).thenReturn(true)
            `when`(eventCache.getRemainingSeats(1L)).thenReturn(0L)

            val exception = assertThrows(ServerException::class.java) {
                service.enqueue("1", 1L, seatId = 10L)
            }
            assertEquals("No remaining seats", exception.message)
            assertEquals("NO_REMAINING_SEATS", exception.code)
        }

        @Test
        @DisplayName("이벤트가 열리지 않았으면 EVENT_NOT_OPEN 예외를 발생시킨다")
        fun enqueueEventNotOpen() {
            `when`(eventCache.exists(1L)).thenReturn(false)

            val exception = assertThrows(ServerException::class.java) {
                service.enqueue("1", 1L, seatId = 10L)
            }
            assertEquals("Event is not open for reservations", exception.message)
            assertEquals("EVENT_NOT_OPEN", exception.code)
        }

        @Test
        @DisplayName("SEAT_PICK 이벤트에 seatId 없이 요청하면 INVALID_REQUEST 예외를 발생시킨다")
        fun enqueueSeatPickWithoutSeatId() {
            `when`(eventCache.exists(1L)).thenReturn(true)
            `when`(eventCache.getRemainingSeats(1L)).thenReturn(100L)
            `when`(eventCache.getSeatSelectionType(1L)).thenReturn("SEAT_PICK")

            val exception = assertThrows(ServerException::class.java) {
                service.enqueue("1", 1L, section = "A")
            }
            assertEquals("INVALID_REQUEST", exception.code)
        }

        @Test
        @DisplayName("SECTION_SELECT 이벤트에 section 없이 요청하면 INVALID_REQUEST 예외를 발생시킨다")
        fun enqueueSectionSelectWithoutSection() {
            `when`(eventCache.exists(1L)).thenReturn(true)
            `when`(eventCache.getRemainingSeats(1L)).thenReturn(100L)
            `when`(eventCache.getSeatSelectionType(1L)).thenReturn("SECTION_SELECT")

            val exception = assertThrows(ServerException::class.java) {
                service.enqueue("1", 1L, seatId = 10L)
            }
            assertEquals("INVALID_REQUEST", exception.code)
        }

        @Test
        @DisplayName("이미 예약된 좌석이면 SEAT_ALREADY_RESERVED 예외를 발생시킨다")
        fun enqueueSeatAlreadyReserved() {
            `when`(eventCache.exists(1L)).thenReturn(true)
            `when`(eventCache.getRemainingSeats(1L)).thenReturn(100L)
            `when`(eventCache.getSeatSelectionType(1L)).thenReturn("SEAT_PICK")
            `when`(eventCache.getSeatStatus(1L, 10L)).thenReturn("A:A-1:RESERVED")

            val exception = assertThrows(ServerException::class.java) {
                service.enqueue("1", 1L, seatId = 10L)
            }
            assertEquals("SEAT_ALREADY_RESERVED", exception.code)
        }

        @Test
        @DisplayName("이미 대기열에 있는 유저는 ALREADY_IN_QUEUE 예외를 발생시킨다")
        fun enqueueAlreadyInQueue() {
            `when`(eventCache.exists(1L)).thenReturn(true)
            `when`(eventCache.getRemainingSeats(1L)).thenReturn(100L)
            `when`(eventCache.getSeatSelectionType(1L)).thenReturn("SECTION_SELECT")
            `when`(queueCache.isInQueue(1L, "1")).thenReturn(true)

            val exception = assertThrows(ServerException::class.java) {
                service.enqueue("1", 1L, section = "A")
            }
            assertEquals("ALREADY_IN_QUEUE", exception.code)
        }
    }

    @Nested
    @DisplayName("peekWaiting - 대기열 조회")
    inner class PeekWaiting {

        @Test
        @DisplayName("대기열에서 지정 수만큼 조회한다")
        fun peekWaitingReturnsUsers() {
            `when`(queueCache.peekQueue(1L, 3)).thenReturn(setOf("1", "2"))
            assertEquals(setOf("1", "2"), service.peekWaiting(1L, 3))
        }
    }

    @Nested
    @DisplayName("removeFromWaiting - 대기열에서 제거")
    inner class RemoveFromWaiting {

        @Test
        @DisplayName("대기열에서 유저를 제거하고 메타데이터를 삭제한다")
        fun removeFromWaitingSuccess() {
            service.removeFromWaiting(1L, "1")
            verify(queueCache).removeFromQueue(1L, "1")
            verify(queueCache).deleteMetadata(1L, "1")
        }
    }

    @Nested
    @DisplayName("getRequestData - 요청 메타데이터 조회")
    inner class GetRequestData {

        @Test
        @DisplayName("seatId가 포함된 요청 데이터를 반환한다")
        fun getRequestDataWithSeatId() {
            `when`(queueCache.getMetadata(1L, "1")).thenReturn(mapOf("seatId" to "10"))

            val data = service.getRequestData(1L, "1")

            assertNotNull(data)
            assertEquals(1L, data!!.eventId)
            assertEquals(10L, data.seatId)
        }

        @Test
        @DisplayName("데이터가 없으면 null을 반환한다")
        fun getRequestDataEmpty() {
            `when`(queueCache.getMetadata(1L, "1")).thenReturn(emptyMap())
            assertNull(service.getRequestData(1L, "1"))
        }
    }

    @Nested
    @DisplayName("cancel - 예약 취소")
    inner class Cancel {

        @Test
        @DisplayName("대기열에서 취소하면 true, 좌석 미예약이면 remainingSeats 변경 없음")
        fun cancelFromQueueOnly() {
            `when`(queueCache.removeFromQueue(1L, "1")).thenReturn(1L)
            `when`(seatService.releaseSeat(1L, 1L))
                .thenReturn(ReservationResult(1L, 1L, 0, false, "No reserved seat found"))

            assertTrue(service.cancel(1L, "1"))
            verify(queueCache).deleteMetadata(1L, "1")
            verify(eventCache, never()).adjustSeatCounts(anyLong(), anyLong(), anyString())
        }

        @Test
        @DisplayName("좌석이 이미 예약된 상태에서 취소하면 좌석 해제 + remainingSeats 증가")
        fun cancelWithReservedSeat() {
            `when`(queueCache.removeFromQueue(1L, "1")).thenReturn(0L)
            `when`(seatService.releaseSeat(1L, 1L))
                .thenReturn(ReservationResult(1L, 1L, 10L, true, "Seat released", "A"))

            assertTrue(service.cancel(1L, "1"))
            verify(eventCache).adjustSeatCounts(1L, 1, "A")
        }

        @Test
        @DisplayName("큐에도 없고 좌석도 없으면 false를 반환한다")
        fun cancelNotFound() {
            `when`(queueCache.removeFromQueue(1L, "1")).thenReturn(0L)
            `when`(seatService.releaseSeat(1L, 1L))
                .thenReturn(ReservationResult(1L, 1L, 0, false, "No reserved seat found"))

            assertFalse(service.cancel(1L, "1"))
        }
    }

    @Nested
    @DisplayName("getPosition - 대기열 위치 조회")
    inner class GetPosition {

        @Test
        @DisplayName("대기열에 있는 유저의 위치를 반환한다")
        fun getPositionFound() {
            `when`(queueCache.getQueuePosition(1L, "1")).thenReturn(3L)
            assertEquals(3L, service.getPosition(1L, "1"))
        }

        @Test
        @DisplayName("대기열에 없는 유저면 null을 반환한다")
        fun getPositionNotFound() {
            `when`(queueCache.getQueuePosition(1L, "1")).thenReturn(null)
            assertNull(service.getPosition(1L, "1"))
        }
    }
}

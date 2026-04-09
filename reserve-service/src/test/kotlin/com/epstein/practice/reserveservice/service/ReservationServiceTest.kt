package com.epstein.practice.reserveservice.service

import com.epstein.practice.reserveservice.dto.SeatDTO
import com.epstein.practice.reserveservice.dto.SectionAvailabilityResponse
import com.epstein.practice.reserveservice.entity.SeatStatus
import com.epstein.practice.reserveservice.repository.SeatRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.Mockito.lenient
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
class ReservationServiceTest {

    @Mock
    lateinit var redis: StringRedisTemplate

    @Mock
    lateinit var zSetOps: ZSetOperations<String, String>

    @Mock
    lateinit var hashOps: HashOperations<String, String, String>

    @Mock
    lateinit var seatRepository: SeatRepository

    private lateinit var queueService: ReservationService

    @BeforeEach
    fun setUp() {
        lenient().`when`(redis.opsForZSet()).thenReturn(zSetOps)
        lenient().`when`(redis.opsForHash<String, String>()).thenReturn(hashOps)
        queueService = ReservationService(redis, seatRepository)
    }

    @Nested
    @DisplayName("enqueue - 대기열에 추가")
    inner class Enqueue {

        @Test
        @DisplayName("이벤트가 열려있으면 좌석 ID로 대기열에 추가한다")
        fun enqueueBySeatId() {
            `when`(redis.hasKey("event:1")).thenReturn(true)
            `when`(hashOps.get("event:1", "remainingSeats")).thenReturn("100")
            `when`(hashOps.get("event:1", "seatSelectionType")).thenReturn("SEAT_PICK")
            `when`(hashOps.get("event:1:seats", "10")).thenReturn("A-1:A:AVAILABLE")
            `when`(zSetOps.score("reservation:waiting:1", "user-1")).thenReturn(null)

            queueService.enqueue("user-1", 1L, seatId = 10L)

            verify(zSetOps).add(eq("reservation:waiting:1"), eq("user-1"), anyDouble())
            verify(hashOps).putAll(eq("reservation:metadata:user-1"), Mockito.argThat { map ->
                map["eventId"] == "1" && map["seatId"] == "10"
            })
        }

        @Test
        @DisplayName("이벤트가 열려있으면 구역으로 대기열에 추가한다")
        fun enqueueBySection() {
            `when`(redis.hasKey("event:1")).thenReturn(true)
            `when`(hashOps.get("event:1", "remainingSeats")).thenReturn("100")
            `when`(hashOps.get("event:1", "seatSelectionType")).thenReturn("SECTION_SELECT")
            `when`(zSetOps.score("reservation:waiting:1", "user-1")).thenReturn(null)

            queueService.enqueue("user-1", 1L, section = "A")

            verify(zSetOps).add(eq("reservation:waiting:1"), eq("user-1"), anyDouble())
            verify(hashOps).putAll(eq("reservation:metadata:user-1"), Mockito.argThat { map ->
                map["eventId"] == "1" && map["section"] == "A" && !map.containsKey("seatId")
            })
        }

        @Test
        @DisplayName("잔여석이 없으면 예외를 발생시킨다")
        fun enqueueNoRemainingSeats() {
            `when`(redis.hasKey("event:1")).thenReturn(true)
            `when`(hashOps.get("event:1", "remainingSeats")).thenReturn("0")

            val exception = assertThrows(IllegalStateException::class.java) {
                queueService.enqueue("user-1", 1L, seatId = 10L)
            }
            assertEquals("No remaining seats", exception.message)
            verify(zSetOps, never()).add(anyString(), anyString(), anyDouble())
        }

        @Test
        @DisplayName("이벤트가 열리지 않았으면 예외를 발생시킨다")
        fun enqueueEventNotOpen() {
            `when`(redis.hasKey("event:1")).thenReturn(false)

            val exception = assertThrows(IllegalStateException::class.java) {
                queueService.enqueue("user-1", 1L, seatId = 10L)
            }
            assertEquals("Event is not open for reservations", exception.message)
            verify(zSetOps, never()).add(anyString(), anyString(), anyDouble())
        }

        @Test
        @DisplayName("SEAT_PICK 이벤트에 seatId 없이 요청하면 예외를 발생시킨다")
        fun enqueueSeatPickWithoutSeatId() {
            `when`(redis.hasKey("event:1")).thenReturn(true)
            `when`(hashOps.get("event:1", "remainingSeats")).thenReturn("100")
            `when`(hashOps.get("event:1", "seatSelectionType")).thenReturn("SEAT_PICK")

            val exception = assertThrows(IllegalArgumentException::class.java) {
                queueService.enqueue("user-1", 1L, section = "A")
            }
            assertEquals("SEAT_PICK events require a specific seatId", exception.message)
            verify(zSetOps, never()).add(anyString(), anyString(), anyDouble())
        }

        @Test
        @DisplayName("SECTION_SELECT 이벤트에 section 없이 요청하면 예외를 발생시킨다")
        fun enqueueSectionSelectWithoutSection() {
            `when`(redis.hasKey("event:1")).thenReturn(true)
            `when`(hashOps.get("event:1", "remainingSeats")).thenReturn("100")
            `when`(hashOps.get("event:1", "seatSelectionType")).thenReturn("SECTION_SELECT")

            val exception = assertThrows(IllegalArgumentException::class.java) {
                queueService.enqueue("user-1", 1L, seatId = 10L)
            }
            assertEquals("SECTION_SELECT events require a section", exception.message)
            verify(zSetOps, never()).add(anyString(), anyString(), anyDouble())
        }

        @Test
        @DisplayName("이미 예약된 좌석이면 예외를 발생시킨다")
        fun enqueueSeatAlreadyReserved() {
            `when`(redis.hasKey("event:1")).thenReturn(true)
            `when`(hashOps.get("event:1", "remainingSeats")).thenReturn("100")
            `when`(hashOps.get("event:1", "seatSelectionType")).thenReturn("SEAT_PICK")
            `when`(hashOps.get("event:1:seats", "10")).thenReturn("A-1:A:RESERVED")

            val exception = assertThrows(IllegalStateException::class.java) {
                queueService.enqueue("user-1", 1L, seatId = 10L)
            }
            assertEquals("Seat is already reserved", exception.message)
            verify(zSetOps, never()).add(anyString(), anyString(), anyDouble())
        }

        @Test
        @DisplayName("이미 대기열에 있는 유저는 중복 추가할 수 없다")
        fun enqueueAlreadyInQueue() {
            `when`(redis.hasKey("event:1")).thenReturn(true)
            `when`(hashOps.get("event:1", "remainingSeats")).thenReturn("100")
            `when`(hashOps.get("event:1", "seatSelectionType")).thenReturn("SECTION_SELECT")
            `when`(zSetOps.score("reservation:waiting:1", "user-1")).thenReturn(1000.0)

            val exception = assertThrows(IllegalStateException::class.java) {
                queueService.enqueue("user-1", 1L, section = "A")
            }
            assertEquals("Already in queue", exception.message)
            verify(zSetOps, never()).add(anyString(), anyString(), anyDouble())
        }
    }

    @Nested
    @DisplayName("peekWaiting - 대기열 조회")
    inner class PeekWaiting {

        @Test
        @DisplayName("대기열에서 지정 수만큼 조회한다")
        fun peekWaitingReturnsUsers() {
            val users = setOf("user-1", "user-2")
            `when`(zSetOps.range("reservation:waiting:1", 0, 2)).thenReturn(users)

            val result = queueService.peekWaiting(1, 3)

            assertEquals(users, result)
        }

        @Test
        @DisplayName("대기열이 비어있으면 빈 집합을 반환한다")
        fun peekWaitingEmpty() {
            `when`(zSetOps.range("reservation:waiting:1", 0, 4)).thenReturn(emptySet())

            assertTrue(queueService.peekWaiting(1, 5).isEmpty())
        }

        @Test
        @DisplayName("range가 null이면 빈 집합을 반환한다")
        fun peekWaitingNull() {
            `when`(zSetOps.range("reservation:waiting:1", 0, 9)).thenReturn(null)

            assertTrue(queueService.peekWaiting(1, 10).isEmpty())
        }
    }

    @Nested
    @DisplayName("removeFromWaiting - 대기열에서 제거")
    inner class RemoveFromWaiting {

        @Test
        @DisplayName("대기열에서 유저를 제거하고 메타데이터를 삭제한다")
        fun removeFromWaitingSuccess() {
            queueService.removeFromWaiting(1, "user-1")

            verify(zSetOps).remove("reservation:waiting:1", "user-1")
            verify(redis).delete("reservation:metadata:user-1")
        }
    }

    @Nested
    @DisplayName("getRequestData - 요청 메타데이터 조회")
    inner class GetRequestData {

        @Test
        @DisplayName("seatId가 포함된 요청 데이터를 반환한다")
        fun getRequestDataWithSeatId() {
            `when`(hashOps.entries("reservation:metadata:user-1"))
                .thenReturn(mapOf("eventId" to "1", "seatId" to "10"))

            val data = queueService.getRequestData("user-1")

            assertNotNull(data)
            assertEquals(1L, data!!.eventId)
            assertEquals(10L, data.seatId)
            assertNull(data.section)
        }

        @Test
        @DisplayName("section이 포함된 요청 데이터를 반환한다")
        fun getRequestDataWithSection() {
            `when`(hashOps.entries("reservation:metadata:user-1"))
                .thenReturn(mapOf("eventId" to "1", "section" to "A"))

            val data = queueService.getRequestData("user-1")

            assertNotNull(data)
            assertEquals(1L, data!!.eventId)
            assertNull(data.seatId)
            assertEquals("A", data.section)
        }

        @Test
        @DisplayName("데이터가 없으면 null을 반환한다")
        fun getRequestDataEmpty() {
            `when`(hashOps.entries("reservation:metadata:user-1")).thenReturn(emptyMap())

            assertNull(queueService.getRequestData("user-1"))
        }

        @Test
        @DisplayName("eventId가 없으면 null을 반환한다")
        fun getRequestDataNoEventId() {
            `when`(hashOps.entries("reservation:metadata:user-1"))
                .thenReturn(mapOf("seatId" to "10"))

            assertNull(queueService.getRequestData("user-1"))
        }
    }

    @Nested
    @DisplayName("cancel - 예약 취소")
    inner class Cancel {

        @Test
        @DisplayName("대기열에서 취소하면 true를 반환한다")
        fun cancelSuccess() {
            `when`(hashOps.entries("reservation:metadata:user-1"))
                .thenReturn(mapOf("eventId" to "1"))
            `when`(zSetOps.remove("reservation:waiting:1", "user-1")).thenReturn(1L)

            assertTrue(queueService.cancel("user-1"))
            verify(redis).delete("reservation:metadata:user-1")
            verify(hashOps).increment(eq("event:1"), eq("remainingSeats"), eq(1L))
        }

        @Test
        @DisplayName("대기열에 없는 유저면 false를 반환한다")
        fun cancelNotFound() {
            `when`(hashOps.entries("reservation:metadata:user-1"))
                .thenReturn(emptyMap())

            assertFalse(queueService.cancel("user-1"))
        }
    }

    @Nested
    @DisplayName("getPosition - 대기열 위치 조회")
    inner class GetPosition {

        @Test
        @DisplayName("대기열에 있는 유저의 위치를 반환한다")
        fun getPositionFound() {
            `when`(hashOps.entries("reservation:metadata:user-1"))
                .thenReturn(mapOf("eventId" to "1"))
            `when`(zSetOps.rank("reservation:waiting:1", "user-1")).thenReturn(3L)

            assertEquals(3L, queueService.getPosition("user-1"))
        }

        @Test
        @DisplayName("대기열에 없는 유저면 null을 반환한다")
        fun getPositionNotFound() {
            `when`(hashOps.entries("reservation:metadata:user-1"))
                .thenReturn(emptyMap())

            assertNull(queueService.getPosition("user-1"))
        }
    }

    @Nested
    @DisplayName("getSectionAvailability - 구역별 잔여석 조회")
    inner class GetSectionAvailability {

        @Test
        @DisplayName("Redis 캐시에서 구역별 잔여석을 반환한다")
        fun getSectionAvailabilityFromRedis() {
            `when`(hashOps.entries("event:1")).thenReturn(mapOf(
                "name" to "Concert",
                "remainingSeats" to "50",
                "section:A:available" to "25",
                "section:A:total" to "30",
                "section:B:available" to "20",
                "section:B:total" to "30"
            ))

            val result = queueService.getSectionAvailability(1L)

            assertEquals(2, result.size)
            assertEquals("A", result[0].section)
            assertEquals(25L, result[0].availableCount)
            assertEquals(30L, result[0].totalCount)
            assertEquals("B", result[1].section)
            assertEquals(20L, result[1].availableCount)
        }

        @Test
        @DisplayName("캐시가 없으면 빈 리스트를 반환한다")
        fun getSectionAvailabilityEmpty() {
            `when`(hashOps.entries("event:1")).thenReturn(emptyMap())

            assertTrue(queueService.getSectionAvailability(1L).isEmpty())
        }
    }
}

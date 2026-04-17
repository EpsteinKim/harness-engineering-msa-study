package com.epstein.practice.reserveservice.cache

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import java.time.Duration

@ExtendWith(MockitoExtension::class)
class EventCacheRepositoryTest {

    @Mock
    lateinit var redis: StringRedisTemplate

    @Mock
    lateinit var hashOps: HashOperations<String, String, String>

    private lateinit var eventCache: EventCacheRepository

    @BeforeEach
    fun setUp() {
        lenient().`when`(redis.opsForHash<String, String>()).thenReturn(hashOps)
        eventCache = EventCacheRepository(redis)
    }

    @Nested
    @DisplayName("Event Cache")
    inner class EventCacheOps {

        @Test
        @DisplayName("이벤트 캐시를 저장한다")
        fun saveEvent() {
            val fields = mapOf("name" to "Concert", "remainingSeats" to "50")
            eventCache.saveEvent(1L, fields)
            verify(hashOps).putAll("event:1", fields)
        }

        @Test
        @DisplayName("이벤트 캐시 존재 여부를 확인한다")
        fun exists() {
            `when`(redis.hasKey("event:1")).thenReturn(true)
            assertTrue(eventCache.exists(1L))

            `when`(redis.hasKey("event:2")).thenReturn(false)
            assertFalse(eventCache.exists(2L))
        }

        @Test
        @DisplayName("잔여석을 조회한다")
        fun getRemainingSeats() {
            `when`(hashOps.get("event:1", "remainingSeats")).thenReturn("50")
            assertEquals(50L, eventCache.getRemainingSeats(1L))
        }

        @Test
        @DisplayName("잔여석이 없으면 0을 반환한다")
        fun getRemainingSeatsNull() {
            `when`(hashOps.get("event:1", "remainingSeats")).thenReturn(null)
            assertEquals(0L, eventCache.getRemainingSeats(1L))
        }

        @Test
        @DisplayName("좌석 선택 타입을 조회한다")
        fun getSeatSelectionType() {
            `when`(hashOps.get("event:1", "seatSelectionType")).thenReturn("SEAT_PICK")
            assertEquals("SEAT_PICK", eventCache.getSeatSelectionType(1L))
        }

        @Test
        @DisplayName("좌석 선택 타입이 없으면 SECTION_SELECT 기본값")
        fun getSeatSelectionTypeDefault() {
            `when`(hashOps.get("event:1", "seatSelectionType")).thenReturn(null)
            assertEquals("SECTION_SELECT", eventCache.getSeatSelectionType(1L))
        }

        @Test
        @DisplayName("remainingSeats와 section available을 동시 증감한다")
        fun adjustSeatCounts() {
            eventCache.adjustSeatCounts(1L, -1, "A")
            verify(hashOps).increment("event:1", "remainingSeats", -1)
            verify(hashOps).increment("event:1", "section:A:available", -1)
        }

        @Test
        @DisplayName("section 없이 remainingSeats만 증감한다")
        fun adjustSeatCountsNoSection() {
            eventCache.adjustSeatCounts(1L, 1)
            verify(hashOps).increment("event:1", "remainingSeats", 1)
            verify(hashOps, never()).increment(eq("event:1"), startsWith("section:"), anyLong())
        }
    }

    @Nested
    @DisplayName("Seat Cache")
    inner class SeatCacheOps {

        @Test
        @DisplayName("좌석 캐시를 저장한다")
        fun saveAllSeats() {
            val fields = mapOf("10" to "A:A-1:AVAILABLE")
            eventCache.saveAllSeats(1L, fields)
            verify(hashOps).putAll("event:1:seats", fields)
        }

        @Test
        @DisplayName("개별 좌석 상태를 조회한다")
        fun getSeatStatus() {
            `when`(hashOps.get("event:1:seats", "10")).thenReturn("A:A-1:AVAILABLE")
            assertEquals("A:A-1:AVAILABLE", eventCache.getSeatStatus(1L, 10L))
        }

        @Test
        @DisplayName("좌석을 RESERVED로 변경한다")
        fun markSeatReserved() {
            `when`(hashOps.get("event:1:seats", "10")).thenReturn("A:A-1:AVAILABLE")
            eventCache.markSeatReserved(1L, 10L)
            verify(hashOps).put("event:1:seats", "10", "A:A-1:RESERVED")
        }

        @Test
        @DisplayName("좌석이 없으면 markSeatReserved는 아무 동작 안함")
        fun markSeatReservedNotFound() {
            `when`(hashOps.get("event:1:seats", "10")).thenReturn(null)
            eventCache.markSeatReserved(1L, 10L)
            verify(hashOps, never()).put(anyString(), anyString(), anyString())
        }

        @Test
        @DisplayName("getAllSeatFields - seat 해시의 모든 필드를 반환")
        fun getAllSeatFields() {
            val entries = mapOf(
                "10" to "A:A-1:AVAILABLE",
                "11" to "A:A-2:RESERVED"
            )
            `when`(hashOps.entries("event:1:seats")).thenReturn(entries)
            assertEquals(entries, eventCache.getAllSeatFields(1L))
        }
    }

    @Nested
    @DisplayName("Hold / Release (Lua)")
    inner class HoldReleaseOps {

        @Test
        @DisplayName("tryHoldSeat - Lua 반환 1L이면 true")
        fun tryHoldSeatSuccess() {
            doReturn(1L).`when`(redis).execute(
                any<RedisScript<Long>>(),
                anyList<String>(),
                any(), any(), any(), any()
            )

            val ok = eventCache.tryHoldSeat(1L, 10L, "user-1", 1000L, 60000L)
            assertTrue(ok)

            verify(redis).execute(
                any<RedisScript<Long>>(),
                anyList<String>(),
                any(), any(), any(), any()
            )
        }

        @Test
        @DisplayName("tryHoldSeat - Lua 반환 0L이면 false")
        fun tryHoldSeatFailure() {
            doReturn(0L).`when`(redis).execute(
                any<RedisScript<Long>>(),
                anyList<String>(),
                any(), any(), any(), any()
            )

            val ok = eventCache.tryHoldSeat(1L, 10L, "user-1", 1000L, 60000L)
            assertFalse(ok)
        }

        @Test
        @DisplayName("releaseHold - Lua 반환 1L이면 true")
        fun releaseHoldSuccess() {
            doReturn(1L).`when`(redis).execute(
                any<RedisScript<Long>>(),
                anyList<String>(),
                any(), any()
            )

            val ok = eventCache.releaseHold(1L, 10L, "user-1")
            assertTrue(ok)

            verify(redis).execute(
                any<RedisScript<Long>>(),
                anyList<String>(),
                any(), any()
            )
        }

        @Test
        @DisplayName("releaseHold - Lua 반환 0L이면 false")
        fun releaseHoldFailure() {
            doReturn(0L).`when`(redis).execute(
                any<RedisScript<Long>>(),
                anyList<String>(),
                any(), any()
            )

            val ok = eventCache.releaseHold(1L, 10L, "user-1")
            assertFalse(ok)
        }

        @Test
        @DisplayName("getSectionAvailable - 숫자 필드값을 Long으로 반환")
        fun getSectionAvailableNumber() {
            `when`(hashOps.get("event:1", "section:A:available")).thenReturn("7")
            assertEquals(7L, eventCache.getSectionAvailable(1L, "A"))
        }

        @Test
        @DisplayName("getSectionAvailable - null이면 0 반환")
        fun getSectionAvailableNull() {
            `when`(hashOps.get("event:1", "section:A:available")).thenReturn(null)
            assertEquals(0L, eventCache.getSectionAvailable(1L, "A"))
        }

        @Test
        @DisplayName("getSectionAvailable - 숫자가 아니면 0 반환")
        fun getSectionAvailableNonNumeric() {
            `when`(hashOps.get("event:1", "section:A:available")).thenReturn("abc")
            assertEquals(0L, eventCache.getSectionAvailable(1L, "A"))
        }
    }
}

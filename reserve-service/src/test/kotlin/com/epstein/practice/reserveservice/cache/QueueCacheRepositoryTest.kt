package com.epstein.practice.reserveservice.main.cache

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
import org.springframework.data.redis.core.ZSetOperations

@ExtendWith(MockitoExtension::class)
class QueueCacheRepositoryTest {

    @Mock
    lateinit var redis: StringRedisTemplate

    @Mock
    lateinit var zSetOps: ZSetOperations<String, String>

    @Mock
    lateinit var hashOps: HashOperations<String, String, String>

    private lateinit var queueCache: QueueCacheRepository

    @BeforeEach
    fun setUp() {
        lenient().`when`(redis.opsForZSet()).thenReturn(zSetOps)
        lenient().`when`(redis.opsForHash<String, String>()).thenReturn(hashOps)
        queueCache = QueueCacheRepository(redis)
    }

    @Nested
    @DisplayName("Waiting Queue")
    inner class WaitingQueue {

        @Test
        @DisplayName("대기열에서 제거한다 (removeFromQueue)")
        fun removeFromQueueVerify() {
            `when`(zSetOps.remove("reservation:waiting:1", "2")).thenReturn(1L)
            assertEquals(1L, queueCache.removeFromQueue(1L, "2"))
        }

        @Test
        @DisplayName("대기열에서 제거한다")
        fun removeFromQueue() {
            `when`(zSetOps.remove("reservation:waiting:1", "1")).thenReturn(1L)
            assertEquals(1L, queueCache.removeFromQueue(1L, "1"))
        }

        @Test
        @DisplayName("대기열 존재 여부를 확인한다")
        fun isInQueue() {
            `when`(zSetOps.score("reservation:waiting:1", "1")).thenReturn(1000.0)
            assertTrue(queueCache.isInQueue(1L, "1"))

            `when`(zSetOps.score("reservation:waiting:1", "2")).thenReturn(null)
            assertFalse(queueCache.isInQueue(1L, "2"))
        }

        @Test
        @DisplayName("대기열 위치를 조회한다")
        fun getQueuePosition() {
            `when`(zSetOps.rank("reservation:waiting:1", "1")).thenReturn(3L)
            assertEquals(3L, queueCache.getQueuePosition(1L, "1"))
        }
    }

    @Nested
    @DisplayName("Seat Hold")
    inner class SeatHold {

        @Test
        @DisplayName("좌석 hold를 저장한다")
        fun holdSeat() {
            queueCache.holdSeat(1L, "1", 10L)
            verify(hashOps).put("seat_held:1", "1", "10")
        }

        @Test
        @DisplayName("hold된 좌석 ID를 조회한다")
        fun getHeldSeatId() {
            `when`(hashOps.get("seat_held:1", "1")).thenReturn("10")
            assertEquals(10L, queueCache.getHeldSeatId(1L, "1"))
        }

        @Test
        @DisplayName("hold된 좌석이 없으면 null을 반환한다")
        fun getHeldSeatIdNull() {
            `when`(hashOps.get("seat_held:1", "1")).thenReturn(null)
            assertNull(queueCache.getHeldSeatId(1L, "1"))
        }

        @Test
        @DisplayName("좌석 hold를 해제한다")
        fun releaseHeldSeat() {
            queueCache.releaseHeldSeat(1L, "1")
            verify(hashOps).delete("seat_held:1", "1")
        }
    }
}

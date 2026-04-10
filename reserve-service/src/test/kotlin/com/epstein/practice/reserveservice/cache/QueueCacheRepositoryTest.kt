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
        @DisplayName("대기열에 추가한다")
        fun addToQueue() {
            queueCache.addToQueue(1L, "1", 1000.0)
            verify(zSetOps).add("reservation:waiting:1", "1", 1000.0)
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
        @DisplayName("대기열 상위 N명을 조회한다")
        fun peekQueue() {
            `when`(zSetOps.range("reservation:waiting:1", 0, 9)).thenReturn(setOf("1", "2"))
            assertEquals(setOf("1", "2"), queueCache.peekQueue(1L, 10))
        }

        @Test
        @DisplayName("대기열 위치를 조회한다")
        fun getQueuePosition() {
            `when`(zSetOps.rank("reservation:waiting:1", "1")).thenReturn(3L)
            assertEquals(3L, queueCache.getQueuePosition(1L, "1"))
        }
    }

    @Nested
    @DisplayName("Request Metadata")
    inner class Metadata {

        @Test
        @DisplayName("메타데이터를 저장한다")
        fun saveMetadata() {
            val data = mapOf("seatId" to "10")
            queueCache.saveMetadata(1L, "1", data)
            verify(hashOps).putAll("reservation:metadata:1:1", data)
        }

        @Test
        @DisplayName("메타데이터를 조회한다")
        fun getMetadata() {
            `when`(hashOps.entries("reservation:metadata:1:1"))
                .thenReturn(mapOf("seatId" to "10"))
            assertEquals(mapOf("seatId" to "10"), queueCache.getMetadata(1L, "1"))
        }

        @Test
        @DisplayName("메타데이터를 삭제한다")
        fun deleteMetadata() {
            queueCache.deleteMetadata(1L, "1")
            verify(redis).delete("reservation:metadata:1:1")
        }
    }
}

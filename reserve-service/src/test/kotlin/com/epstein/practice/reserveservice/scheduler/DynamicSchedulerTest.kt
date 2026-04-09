package com.epstein.practice.reserveservice.scheduler

import com.epstein.practice.reserveservice.service.ReservationResult
import com.epstein.practice.reserveservice.service.ReservationService
import com.epstein.practice.reserveservice.service.RequestData
import com.epstein.practice.reserveservice.service.SeatService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.lenient
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations
import org.springframework.scheduling.TaskScheduler
import java.time.Duration
import java.util.concurrent.ScheduledFuture

@ExtendWith(MockitoExtension::class)
class DynamicSchedulerTest {

    @Mock
    lateinit var taskScheduler: TaskScheduler

    @Mock
    lateinit var queueService: ReservationService

    @Mock
    lateinit var seatService: SeatService

    @Mock
    lateinit var redis: StringRedisTemplate

    @Mock
    lateinit var hashOps: HashOperations<String, String, String>

    @Mock
    lateinit var zSetOps: ZSetOperations<String, String>

    @Mock
    lateinit var scheduledFuture: ScheduledFuture<*>

    private lateinit var scheduler: DynamicScheduler

    @Nested
    @DisplayName("processEvent - 대기열 처리")
    inner class ProcessEvent {

        private lateinit var capturedRunnable: Runnable

        @BeforeEach
        fun setUp() {
            lenient().`when`(redis.opsForHash<String, String>()).thenReturn(hashOps)
            lenient().`when`(redis.opsForZSet()).thenReturn(zSetOps)
            lenient().`when`(taskScheduler.scheduleAtFixedRate(any(Runnable::class.java), any(Duration::class.java)))
                .thenAnswer { invocation ->
                    capturedRunnable = invocation.getArgument(0)
                    scheduledFuture
                }
            scheduler = DynamicScheduler(taskScheduler, queueService, seatService, redis, 10L)
            scheduler.startProcessing(1L)
        }

        @Test
        @DisplayName("대기열이 비어있으면 아무 처리도 하지 않는다")
        fun emptyQueueDoesNothing() {
            `when`(queueService.peekWaiting(1L, 10L)).thenReturn(emptySet())

            capturedRunnable.run()

            verify(queueService).peekWaiting(1L, 10L)
            verify(queueService, never()).getRequestData(anyString())
        }

        @Test
        @DisplayName("구역 요청 성공 시 대기열에서 제거하고 remainingSeats 감소")
        fun sectionReservationSuccessRemovesAndDecrements() {
            `when`(queueService.peekWaiting(1L, 10L)).thenReturn(setOf("user-1"))
            `when`(hashOps.get("event:1", "remainingSeats")).thenReturn("100")
            `when`(zSetOps.score("reservation:waiting:1", "user-1")).thenReturn(1000.0)
            `when`(queueService.getRequestData("user-1"))
                .thenReturn(RequestData(eventId = 1L, section = "A"))
            `when`(seatService.reserveBySection(1L, "A", "user-1"))
                .thenReturn(ReservationResult("user-1", 1L, 5L, true, "Reservation successful", "A"))
            `when`(hashOps.get("event:1", "seatSelectionType")).thenReturn("SECTION_SELECT")

            capturedRunnable.run()

            verify(queueService).removeFromWaiting(1L, "user-1")
            verify(hashOps).increment("event:1", "remainingSeats", -1)
            verify(hashOps).increment("event:1", "section:A:available", -1)
        }

        @Test
        @DisplayName("좌석 ID 요청 성공 시 대기열에서 제거하고 remainingSeats 감소")
        fun seatIdReservationSuccessRemovesAndDecrements() {
            `when`(queueService.peekWaiting(1L, 10L)).thenReturn(setOf("user-1"))
            `when`(hashOps.get("event:1", "remainingSeats")).thenReturn("100")
            `when`(zSetOps.score("reservation:waiting:1", "user-1")).thenReturn(1000.0)
            `when`(queueService.getRequestData("user-1"))
                .thenReturn(RequestData(eventId = 1L, seatId = 10L))
            `when`(seatService.reserveBySeatId(1L, 10L, "user-1"))
                .thenReturn(ReservationResult("user-1", 1L, 10L, true, "Reservation successful", "A"))
            `when`(hashOps.get("event:1", "seatSelectionType")).thenReturn("SECTION_SELECT")

            capturedRunnable.run()

            verify(queueService).removeFromWaiting(1L, "user-1")
            verify(hashOps).increment("event:1", "remainingSeats", -1)
            verify(hashOps).increment("event:1", "section:A:available", -1)
        }

        @Test
        @DisplayName("예약 실패해도 대기열에서 제거하지만 remainingSeats 변경 없음")
        fun reservationFailureRemovesButNoDecrement() {
            `when`(queueService.peekWaiting(1L, 10L)).thenReturn(setOf("user-1"))
            `when`(hashOps.get("event:1", "remainingSeats")).thenReturn("100")
            `when`(zSetOps.score("reservation:waiting:1", "user-1")).thenReturn(1000.0)
            `when`(queueService.getRequestData("user-1"))
                .thenReturn(RequestData(eventId = 1L, seatId = 10L))
            `when`(seatService.reserveBySeatId(1L, 10L, "user-1"))
                .thenReturn(ReservationResult("user-1", 1L, 10L, false, "Seat already reserved"))

            capturedRunnable.run()

            verify(queueService).removeFromWaiting(1L, "user-1")
            verify(hashOps, never()).increment(eq("event:1"), eq("remainingSeats"), anyLong())
            verify(hashOps, never()).increment(eq("event:1"), eq("section:A:available"), anyLong())
        }

        @Test
        @DisplayName("메타데이터가 없으면 대기열에서 제거")
        fun noMetadataRemovesFromQueue() {
            `when`(queueService.peekWaiting(1L, 10L)).thenReturn(setOf("user-1"))
            `when`(hashOps.get("event:1", "remainingSeats")).thenReturn("100")
            `when`(zSetOps.score("reservation:waiting:1", "user-1")).thenReturn(1000.0)
            `when`(queueService.getRequestData("user-1")).thenReturn(null)

            capturedRunnable.run()

            verify(queueService).removeFromWaiting(1L, "user-1")
            verify(seatService, never()).reserveBySeatId(anyLong(), anyLong(), anyString())
            verify(seatService, never()).reserveBySection(anyLong(), anyString(), anyString())
        }

        @Test
        @DisplayName("seatId와 section 모두 없으면 대기열에서 제거")
        fun noSeatIdAndNoSectionRemovesFromQueue() {
            `when`(queueService.peekWaiting(1L, 10L)).thenReturn(setOf("user-1"))
            `when`(hashOps.get("event:1", "remainingSeats")).thenReturn("100")
            `when`(zSetOps.score("reservation:waiting:1", "user-1")).thenReturn(1000.0)
            `when`(queueService.getRequestData("user-1"))
                .thenReturn(RequestData(eventId = 1L))

            capturedRunnable.run()

            verify(queueService).removeFromWaiting(1L, "user-1")
            verify(seatService, never()).reserveBySeatId(anyLong(), anyLong(), anyString())
            verify(seatService, never()).reserveBySection(anyLong(), anyString(), anyString())
        }

        @Test
        @DisplayName("여러 유저를 순차 처리")
        fun processesMultipleUsersSequentially() {
            `when`(queueService.peekWaiting(1L, 10L))
                .thenReturn(setOf("user-1", "user-2", "user-3"))
            `when`(hashOps.get("event:1", "remainingSeats")).thenReturn("100")
            `when`(zSetOps.score("reservation:waiting:1", "user-1")).thenReturn(1000.0)
            `when`(zSetOps.score("reservation:waiting:1", "user-2")).thenReturn(1001.0)
            `when`(zSetOps.score("reservation:waiting:1", "user-3")).thenReturn(1002.0)
            `when`(queueService.getRequestData("user-1"))
                .thenReturn(RequestData(eventId = 1L, section = "A"))
            `when`(queueService.getRequestData("user-2"))
                .thenReturn(RequestData(eventId = 1L, seatId = 20L))
            `when`(queueService.getRequestData("user-3"))
                .thenReturn(null)
            `when`(seatService.reserveBySection(1L, "A", "user-1"))
                .thenReturn(ReservationResult("user-1", 1L, 5L, true, "Reservation successful", "A"))
            `when`(seatService.reserveBySeatId(1L, 20L, "user-2"))
                .thenReturn(ReservationResult("user-2", 1L, 20L, false, "Seat already reserved"))

            capturedRunnable.run()

            verify(queueService).removeFromWaiting(1L, "user-1")
            verify(queueService).removeFromWaiting(1L, "user-2")
            verify(queueService).removeFromWaiting(1L, "user-3")
            verify(hashOps).increment("event:1", "remainingSeats", -1)
        }

        @Test
        @DisplayName("잔여석이 없으면 DB 예약 시도 없이 중단한다")
        fun noRemainingSeatsStopsProcessing() {
            `when`(queueService.peekWaiting(1L, 10L)).thenReturn(setOf("user-1"))
            `when`(hashOps.get("event:1", "remainingSeats")).thenReturn("0")

            capturedRunnable.run()

            verify(queueService, never()).getRequestData(anyString())
            verify(seatService, never()).reserveBySeatId(anyLong(), anyLong(), anyString())
            verify(seatService, never()).reserveBySection(anyLong(), anyString(), anyString())
        }

        @Test
        @DisplayName("SEAT_PICK 예약 성공 시 좌석 캐시 상태를 RESERVED로 변경한다")
        fun seatPickReservationUpdatesSeatCache() {
            `when`(queueService.peekWaiting(1L, 10L)).thenReturn(setOf("user-1"))
            `when`(hashOps.get("event:1", "remainingSeats")).thenReturn("100")
            `when`(zSetOps.score("reservation:waiting:1", "user-1")).thenReturn(1000.0)
            `when`(queueService.getRequestData("user-1"))
                .thenReturn(RequestData(eventId = 1L, seatId = 10L))
            `when`(seatService.reserveBySeatId(1L, 10L, "user-1"))
                .thenReturn(ReservationResult("user-1", 1L, 10L, true, "Reservation successful", "A"))
            `when`(hashOps.get("event:1", "seatSelectionType")).thenReturn("SEAT_PICK")
            `when`(hashOps.get("event:1:seats", "10")).thenReturn("A-1:A:AVAILABLE")

            capturedRunnable.run()

            verify(hashOps).put("event:1:seats", "10", "A-1:A:RESERVED")
        }

        @Test
        @DisplayName("처리 전 대기열에서 제거된 유저는 건너뛴다")
        fun userRemovedFromQueueBeforeProcessing() {
            `when`(queueService.peekWaiting(1L, 10L)).thenReturn(setOf("user-1"))
            `when`(hashOps.get("event:1", "remainingSeats")).thenReturn("100")
            `when`(zSetOps.score("reservation:waiting:1", "user-1")).thenReturn(null)

            capturedRunnable.run()

            verify(queueService, never()).getRequestData(anyString())
            verify(seatService, never()).reserveBySeatId(anyLong(), anyLong(), anyString())
            verify(seatService, never()).reserveBySection(anyLong(), anyString(), anyString())
        }
    }

    @Nested
    @DisplayName("startProcessing / stopProcessing - 태스크 관리")
    inner class StartStopProcessing {

        @BeforeEach
        fun setUp() {
            lenient().`when`(redis.opsForHash<String, String>()).thenReturn(hashOps)
            lenient().`when`(taskScheduler.scheduleAtFixedRate(any(Runnable::class.java), any(Duration::class.java)))
                .thenReturn(scheduledFuture)
            scheduler = DynamicScheduler(taskScheduler, queueService, seatService, redis, 10L)
        }

        @Test
        @DisplayName("startProcessing 시 taskScheduler에 태스크 등록")
        fun startProcessingRegistersTask() {
            scheduler.startProcessing(1L)

            verify(taskScheduler).scheduleAtFixedRate(any(Runnable::class.java), eq(Duration.ofSeconds(1)))
            assertTrue(scheduler.isProcessing(1L))
        }

        @Test
        @DisplayName("이미 처리 중인 이벤트는 중복 등록하지 않음")
        fun startProcessingSkipsDuplicate() {
            scheduler.startProcessing(1L)
            scheduler.startProcessing(1L)

            verify(taskScheduler).scheduleAtFixedRate(any(Runnable::class.java), any(Duration::class.java))
        }

        @Test
        @DisplayName("stopProcessing 시 태스크 취소")
        fun stopProcessingCancelsTask() {
            scheduler.startProcessing(1L)

            scheduler.stopProcessing(1L)

            verify(scheduledFuture).cancel(false)
            assertFalse(scheduler.isProcessing(1L))
        }
    }
}

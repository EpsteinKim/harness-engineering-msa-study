package com.epstein.practice.reserveservice.scheduler

import com.epstein.practice.reserveservice.cache.EventCacheRepository
import com.epstein.practice.reserveservice.cache.QueueCacheRepository
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
    lateinit var eventCache: EventCacheRepository

    @Mock
    lateinit var queueCache: QueueCacheRepository

    @Mock
    lateinit var scheduledFuture: ScheduledFuture<*>

    private lateinit var scheduler: DynamicScheduler

    @Nested
    @DisplayName("processEvent - 대기열 처리")
    inner class ProcessEvent {

        private lateinit var capturedRunnable: Runnable

        @BeforeEach
        fun setUp() {
            lenient().`when`(taskScheduler.scheduleAtFixedRate(any(Runnable::class.java), any(Duration::class.java)))
                .thenAnswer { invocation ->
                    capturedRunnable = invocation.getArgument(0)
                    scheduledFuture
                }
            scheduler = DynamicScheduler(taskScheduler, queueService, seatService, eventCache, queueCache, 10L)
            scheduler.startProcessing(1L)
        }

        @Test
        @DisplayName("대기열이 비어있으면 아무 처리도 하지 않는다")
        fun emptyQueueDoesNothing() {
            `when`(queueService.peekWaiting(1L, 10L)).thenReturn(emptySet())

            capturedRunnable.run()

            verify(queueService, never()).getRequestData(anyLong(), anyString())
        }

        @Test
        @DisplayName("구역 요청 성공 시 대기열에서 제거하고 remainingSeats 감소")
        fun sectionReservationSuccessRemovesAndDecrements() {
            `when`(queueService.peekWaiting(1L, 10L)).thenReturn(setOf("1"))
            `when`(eventCache.getRemainingSeats(1L)).thenReturn(100L)
            `when`(queueCache.isInQueue(1L, "1")).thenReturn(true)
            `when`(queueService.getRequestData(1L, "1"))
                .thenReturn(RequestData(eventId = 1L, section = "A"))
            `when`(seatService.reserveBySection(1L, "A", 1L))
                .thenReturn(ReservationResult(1L, 1L, 5L, true, "reservation successful", "A"))
            `when`(eventCache.getSeatSelectionType(1L)).thenReturn("SECTION_SELECT")

            capturedRunnable.run()

            verify(queueService).removeFromWaiting(1L, "1")
            verify(eventCache).adjustSeatCounts(1L, -1, "A")
        }

        @Test
        @DisplayName("좌석 ID 요청 성공 시 대기열에서 제거하고 remainingSeats 감소")
        fun seatIdReservationSuccessRemovesAndDecrements() {
            `when`(queueService.peekWaiting(1L, 10L)).thenReturn(setOf("1"))
            `when`(eventCache.getRemainingSeats(1L)).thenReturn(100L)
            `when`(queueCache.isInQueue(1L, "1")).thenReturn(true)
            `when`(queueService.getRequestData(1L, "1"))
                .thenReturn(RequestData(eventId = 1L, seatId = 10L))
            `when`(seatService.reserveBySeatId(1L, 10L, 1L))
                .thenReturn(ReservationResult(1L, 1L, 10L, true, "reservation successful", "A"))
            `when`(eventCache.getSeatSelectionType(1L)).thenReturn("SECTION_SELECT")

            capturedRunnable.run()

            verify(queueService).removeFromWaiting(1L, "1")
            verify(eventCache).adjustSeatCounts(1L, -1, "A")
        }

        @Test
        @DisplayName("예약 실패해도 대기열에서 제거하지만 remainingSeats 변경 없음")
        fun reservationFailureRemovesButNoDecrement() {
            `when`(queueService.peekWaiting(1L, 10L)).thenReturn(setOf("1"))
            `when`(eventCache.getRemainingSeats(1L)).thenReturn(100L)
            `when`(queueCache.isInQueue(1L, "1")).thenReturn(true)
            `when`(queueService.getRequestData(1L, "1"))
                .thenReturn(RequestData(eventId = 1L, seatId = 10L))
            `when`(seatService.reserveBySeatId(1L, 10L, 1L))
                .thenReturn(ReservationResult(1L, 1L, 10L, false, "seat already reserved"))

            capturedRunnable.run()

            verify(queueService).removeFromWaiting(1L, "1")
            verify(eventCache, never()).adjustSeatCounts(anyLong(), anyLong(), anyString())
        }

        @Test
        @DisplayName("메타데이터가 없으면 대기열에서 제거")
        fun noMetadataRemovesFromQueue() {
            `when`(queueService.peekWaiting(1L, 10L)).thenReturn(setOf("1"))
            `when`(eventCache.getRemainingSeats(1L)).thenReturn(100L)
            `when`(queueCache.isInQueue(1L, "1")).thenReturn(true)
            `when`(queueService.getRequestData(1L, "1")).thenReturn(null)

            capturedRunnable.run()

            verify(queueService).removeFromWaiting(1L, "1")
            verify(seatService, never()).reserveBySeatId(anyLong(), anyLong(), anyLong())
            verify(seatService, never()).reserveBySection(anyLong(), anyString(), anyLong())
        }

        @Test
        @DisplayName("seatId와 section 모두 없으면 대기열에서 제거")
        fun noSeatIdAndNoSectionRemovesFromQueue() {
            `when`(queueService.peekWaiting(1L, 10L)).thenReturn(setOf("1"))
            `when`(eventCache.getRemainingSeats(1L)).thenReturn(100L)
            `when`(queueCache.isInQueue(1L, "1")).thenReturn(true)
            `when`(queueService.getRequestData(1L, "1"))
                .thenReturn(RequestData(eventId = 1L))

            capturedRunnable.run()

            verify(queueService).removeFromWaiting(1L, "1")
        }

        @Test
        @DisplayName("여러 유저를 순차 처리")
        fun processesMultipleUsersSequentially() {
            `when`(queueService.peekWaiting(1L, 10L))
                .thenReturn(setOf("1", "2", "3"))
            `when`(eventCache.getRemainingSeats(1L)).thenReturn(100L)
            `when`(queueCache.isInQueue(1L, "1")).thenReturn(true)
            `when`(queueCache.isInQueue(1L, "2")).thenReturn(true)
            `when`(queueCache.isInQueue(1L, "3")).thenReturn(true)
            `when`(queueService.getRequestData(1L, "1"))
                .thenReturn(RequestData(eventId = 1L, section = "A"))
            `when`(queueService.getRequestData(1L, "2"))
                .thenReturn(RequestData(eventId = 1L, seatId = 20L))
            `when`(queueService.getRequestData(1L, "3"))
                .thenReturn(null)
            `when`(seatService.reserveBySection(1L, "A", 1L))
                .thenReturn(ReservationResult(1L, 1L, 5L, true, "reservation successful", "A"))
            `when`(seatService.reserveBySeatId(1L, 20L, 2L))
                .thenReturn(ReservationResult(2L, 1L, 20L, false, "seat already reserved"))
            `when`(eventCache.getSeatSelectionType(1L)).thenReturn("SECTION_SELECT")

            capturedRunnable.run()

            verify(queueService).removeFromWaiting(1L, "1")
            verify(queueService).removeFromWaiting(1L, "2")
            verify(queueService).removeFromWaiting(1L, "3")
        }

        @Test
        @DisplayName("잔여석이 없으면 waiting queue 전체를 정리하고 스케줄러 중단")
        fun noRemainingSeatsClearsQueueAndStops() {
            `when`(queueService.peekWaiting(1L, 10L)).thenReturn(setOf("1", "2"))
            `when`(eventCache.getRemainingSeats(1L)).thenReturn(0L)
            `when`(queueCache.peekQueue(1L, Long.MAX_VALUE)).thenReturn(setOf("1", "2", "3"))

            capturedRunnable.run()

            verify(queueService, never()).getRequestData(anyLong(), anyString())
            verify(queueService).removeFromWaiting(1L, "1")
            verify(queueService).removeFromWaiting(1L, "2")
            verify(queueService).removeFromWaiting(1L, "3")
            verify(scheduledFuture).cancel(false)
            assertFalse(scheduler.isProcessing(1L))
        }

        @Test
        @DisplayName("SEAT_PICK 예약 성공 시 좌석 캐시 상태를 RESERVED로 변경한다")
        fun seatPickReservationUpdatesSeatCache() {
            `when`(queueService.peekWaiting(1L, 10L)).thenReturn(setOf("1"))
            `when`(eventCache.getRemainingSeats(1L)).thenReturn(100L)
            `when`(queueCache.isInQueue(1L, "1")).thenReturn(true)
            `when`(queueService.getRequestData(1L, "1"))
                .thenReturn(RequestData(eventId = 1L, seatId = 10L))
            `when`(seatService.reserveBySeatId(1L, 10L, 1L))
                .thenReturn(ReservationResult(1L, 1L, 10L, true, "reservation successful", "A"))
            `when`(eventCache.getSeatSelectionType(1L)).thenReturn("SEAT_PICK")

            capturedRunnable.run()

            verify(eventCache).markSeatReserved(1L, 10L)
            verify(eventCache, never()).releaseHold(anyLong(), anyLong(), anyString())
        }

        @Test
        @DisplayName("SEAT_PICK 예약 실패 시 releaseHold로 HOLD를 복원한다")
        fun seatPickReservationFailureReleasesHold() {
            `when`(queueService.peekWaiting(1L, 10L)).thenReturn(setOf("1"))
            `when`(eventCache.getRemainingSeats(1L)).thenReturn(100L)
            `when`(queueCache.isInQueue(1L, "1")).thenReturn(true)
            `when`(queueService.getRequestData(1L, "1"))
                .thenReturn(RequestData(eventId = 1L, seatId = 10L))
            `when`(seatService.reserveBySeatId(1L, 10L, 1L))
                .thenReturn(ReservationResult(1L, 1L, 10L, false, "seat already reserved"))

            capturedRunnable.run()

            verify(eventCache).releaseHold(1L, 10L, "1")
            verify(eventCache, never()).markSeatReserved(anyLong(), anyLong())
        }

        @Test
        @DisplayName("SEAT_PICK 예약 중 낙관적 락 충돌 시 releaseHold + removeFromWaiting")
        fun seatPickOptimisticLockConflict() {
            `when`(queueService.peekWaiting(1L, 10L)).thenReturn(setOf("1"))
            `when`(eventCache.getRemainingSeats(1L)).thenReturn(100L)
            `when`(queueCache.isInQueue(1L, "1")).thenReturn(true)
            `when`(queueService.getRequestData(1L, "1"))
                .thenReturn(RequestData(eventId = 1L, seatId = 10L))
            `when`(seatService.reserveBySeatId(1L, 10L, 1L))
                .thenThrow(org.springframework.orm.ObjectOptimisticLockingFailureException("Seat", 10L))

            capturedRunnable.run()

            verify(eventCache).releaseHold(1L, 10L, "1")
            verify(queueService).removeFromWaiting(1L, "1")
            verify(eventCache, never()).markSeatReserved(anyLong(), anyLong())
            verify(eventCache, never()).adjustSeatCounts(anyLong(), anyLong(), anyString())
        }

        @Test
        @DisplayName("SECTION_SELECT 예약 실패 시에는 releaseHold가 호출되지 않는다")
        fun sectionSelectReservationFailureDoesNotReleaseHold() {
            `when`(queueService.peekWaiting(1L, 10L)).thenReturn(setOf("1"))
            `when`(eventCache.getRemainingSeats(1L)).thenReturn(100L)
            `when`(queueCache.isInQueue(1L, "1")).thenReturn(true)
            `when`(queueService.getRequestData(1L, "1"))
                .thenReturn(RequestData(eventId = 1L, section = "A"))
            `when`(seatService.reserveBySection(1L, "A", 1L))
                .thenReturn(ReservationResult(1L, 1L, 0L, false, "no available seat in section A"))

            capturedRunnable.run()

            verify(eventCache, never()).releaseHold(anyLong(), anyLong(), anyString())
        }

        @Test
        @DisplayName("처리 전 대기열에서 제거된 유저는 metadata 정리 후 건너뛴다")
        fun userRemovedFromQueueBeforeProcessing() {
            `when`(queueService.peekWaiting(1L, 10L)).thenReturn(setOf("1"))
            `when`(eventCache.getRemainingSeats(1L)).thenReturn(100L)
            `when`(queueCache.isInQueue(1L, "1")).thenReturn(false)

            capturedRunnable.run()

            verify(queueService, never()).getRequestData(anyLong(), anyString())
            verify(queueService).removeFromWaiting(1L, "1")
        }
    }

    @Nested
    @DisplayName("startProcessing / stopProcessing - 태스크 관리")
    inner class StartStopProcessing {

        @BeforeEach
        fun setUp() {
            lenient().`when`(taskScheduler.scheduleAtFixedRate(any(Runnable::class.java), any(Duration::class.java)))
                .thenReturn(scheduledFuture)
            scheduler = DynamicScheduler(taskScheduler, queueService, seatService, eventCache, queueCache, 10L)
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

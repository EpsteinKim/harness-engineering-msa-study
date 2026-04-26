package com.epstein.practice.reserveservice.consumer

import com.epstein.practice.reserveservice.main.cache.EventCacheRepository
import com.epstein.practice.reserveservice.main.cache.QueueCacheRepository
import com.epstein.practice.reserveservice.type.event.EnqueueMessage
import com.epstein.practice.reserveservice.main.service.ReservationResult
import com.epstein.practice.reserveservice.main.service.SeatService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import com.epstein.practice.reserveservice.main.service.SagaOrchestrator
import org.springframework.orm.ObjectOptimisticLockingFailureException

@ExtendWith(MockitoExtension::class)
class QueueConsumerTest {

    @Mock lateinit var seatService: SeatService
    @Mock lateinit var eventCache: EventCacheRepository
    @Mock lateinit var queueCache: QueueCacheRepository
    @Mock lateinit var sagaOrchestrator: SagaOrchestrator

    private lateinit var consumer: QueueConsumer

    @BeforeEach
    fun setUp() {
        consumer = QueueConsumer(seatService, eventCache, queueCache, sagaOrchestrator)
    }

    private fun msg(section: String? = "A", seatId: Long? = null) =
        EnqueueMessage(eventId = 1L, userId = "1", seatId = seatId, section = section, joinedAt = 0L)

    @Test
    @DisplayName("잔여석 음수면 보상 후 유저 드롭, seatService 호출하지 않음")
    fun noRemainingSeats() {
        `when`(eventCache.getRemainingSeats(1L)).thenReturn(-1L)

        consumer.onMessage(msg())

        verify(eventCache).adjustSeatCounts(1L, 1, "A")
        verify(queueCache).releaseHeldSeat(1L, "1")
        verify(seatService, never()).reserveBySection(anyLong(), anyString(), anyLong())
    }

    @Test
    @DisplayName("SECTION_SELECT 성공: Saga 시작 (선차감이므로 adjustSeatCounts 호출 없음)")
    fun sectionSuccess() {
        `when`(eventCache.getRemainingSeats(1L)).thenReturn(10L)
        `when`(seatService.reserveBySection(1L, "A", 1L))
            .thenReturn(ReservationResult(1L, 1L, 100L, true, "seat A-1 reserved", "A"))
        `when`(eventCache.getSeatSelectionType(1L)).thenReturn("SECTION_SELECT")
        `when`(eventCache.getSeatPrice(1L, 100L)).thenReturn(200_000L)

        consumer.onMessage(msg())

        verify(eventCache, never()).adjustSeatCounts(anyLong(), anyLong(), anyString())
        verify(eventCache, never()).markSeatReserved(anyLong(), anyLong())
        verify(queueCache).releaseHeldSeat(1L, "1")
        verify(sagaOrchestrator).startSaga(anyLong(), anyLong(), anyLong(), anyLong())
    }

    @Test
    @DisplayName("SEAT_PICK 성공: markSeatReserved + Saga 시작 (선차감이므로 adjustSeatCounts 호출 없음)")
    fun seatPickSuccess() {
        `when`(eventCache.getRemainingSeats(1L)).thenReturn(10L)
        `when`(seatService.reserveBySeatId(1L, 99L, 1L))
            .thenReturn(ReservationResult(1L, 1L, 99L, true, "ok", "A"))
        `when`(eventCache.getSeatSelectionType(1L)).thenReturn("SEAT_PICK")
        `when`(eventCache.getSeatPrice(1L, 99L)).thenReturn(150_000L)

        consumer.onMessage(msg(section = null, seatId = 99L))

        verify(eventCache, never()).adjustSeatCounts(anyLong(), anyLong(), anyString())
        verify(eventCache).markSeatReserved(1L, 99L)
        verify(sagaOrchestrator).startSaga(anyLong(), anyLong(), anyLong(), anyLong())
    }

    @Test
    @DisplayName("낙관적 락 충돌 시 좌석 수 보상 + HOLD 해제 후 드롭")
    fun optimisticLockReleasesHold() {
        `when`(eventCache.getRemainingSeats(1L)).thenReturn(10L)
        `when`(seatService.reserveBySeatId(1L, 99L, 1L))
            .thenThrow(ObjectOptimisticLockingFailureException("Seat", 99L))

        consumer.onMessage(msg(section = null, seatId = 99L))

        verify(eventCache).adjustSeatCounts(1L, 1, null)
        verify(eventCache).releaseHold(1L, 99L, "1")
        verify(queueCache).releaseHeldSeat(1L, "1")
    }
}

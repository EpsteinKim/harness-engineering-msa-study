package com.epstein.practice.reserveservice.scheduler

import com.epstein.practice.reserveservice.service.EventService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class EventSchedulerTest {

    @Mock
    lateinit var eventService: EventService

    lateinit var scheduler: EventScheduler

    @BeforeEach
    fun setUp() {
        scheduler = EventScheduler(eventService)
    }

    @Nested
    @DisplayName("openEvent - 이벤트 열기/닫기")
    inner class OpenEvent {

        @Test
        @DisplayName("이벤트 열기/닫기를 실행한다")
        fun opensAndClosesEvents() {
            `when`(eventService.openEvents()).thenReturn(2)
            `when`(eventService.closeEvents()).thenReturn(1)

            scheduler.openEvent()

            verify(eventService).openEvents()
            verify(eventService).closeEvents()
        }

        @Test
        @DisplayName("변경할 이벤트가 없어도 정상 동작한다")
        fun noEventsToProcess() {
            `when`(eventService.openEvents()).thenReturn(0)
            `when`(eventService.closeEvents()).thenReturn(0)

            scheduler.openEvent()

            verify(eventService).openEvents()
            verify(eventService).closeEvents()
        }
    }

    @Nested
    @DisplayName("syncRemainingSeats - 잔여 좌석 동기화")
    inner class SyncRemainingSeats {

        @Test
        @DisplayName("잔여 좌석 동기화를 실행하고 동기화된 이벤트 수를 반환한다")
        fun syncsRemainingSeats() {
            `when`(eventService.syncAllRemainingSeats()).thenReturn(3)

            scheduler.syncRemainingSeats()

            verify(eventService).syncAllRemainingSeats()
        }

        @Test
        @DisplayName("동기화할 이벤트가 없어도 정상 동작한다")
        fun noEventsToSync() {
            `when`(eventService.syncAllRemainingSeats()).thenReturn(0)

            scheduler.syncRemainingSeats()

            verify(eventService).syncAllRemainingSeats()
        }
    }
}

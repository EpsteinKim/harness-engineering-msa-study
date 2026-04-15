package com.epstein.practice.reserveservice.service

import com.epstein.practice.reserveservice.cache.EventCacheRepository
import com.epstein.practice.reserveservice.constant.sectionAvailableField
import com.epstein.practice.reserveservice.dto.SectionAvailabilityResponse
import com.epstein.practice.reserveservice.entity.Event
import com.epstein.practice.reserveservice.entity.EventStatus
import com.epstein.practice.reserveservice.entity.Seat
import com.epstein.practice.reserveservice.entity.SeatSelectionType
import com.epstein.practice.reserveservice.entity.SeatStatus
import com.epstein.practice.reserveservice.repository.EventRepository
import com.epstein.practice.reserveservice.repository.SeatRepository
import com.epstein.practice.reserveservice.repository.support.SeatQueryRepository
import com.epstein.practice.reserveservice.scheduler.DynamicScheduler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
class EventLifecycleServiceTest {

    @Mock
    lateinit var eventRepository: EventRepository

    @Mock
    lateinit var seatRepository: SeatRepository

    @Mock
    lateinit var seatQueryRepository: SeatQueryRepository

    @Mock
    lateinit var eventCache: EventCacheRepository

    @Mock
    lateinit var dynamicScheduler: DynamicScheduler

    private lateinit var eventService: EventLifecycleService

    @BeforeEach
    fun setUp() {
        eventService = EventLifecycleService(eventRepository, seatRepository, seatQueryRepository, eventCache, dynamicScheduler)
    }

    @Nested
    @DisplayName("openEvents - 이벤트 열기")
    inner class OpenEvents {

        @Test
        @DisplayName("조건에 맞는 이벤트를 OPEN으로 변경하고 캐싱한다")
        fun opensMatchingEvents() {
            val event = Event(
                id = 1L, name = "Concert",
                eventTime = LocalDateTime.of(2026, 5, 1, 19, 0),
                status = EventStatus.CLOSED,
                ticketOpenTime = LocalDateTime.of(2026, 4, 1, 10, 0),
                ticketCloseTime = LocalDateTime.of(2026, 4, 30, 23, 59),
                seatSelectionType = SeatSelectionType.SECTION_SELECT
            )
            Mockito.doReturn(listOf(event)).`when`(eventRepository)
                .findEventsToOpen(any() ?: EventStatus.CLOSED, any() ?: LocalDateTime.MIN)
            `when`(seatRepository.countAvailableSeats(1L)).thenReturn(50L)
            `when`(seatQueryRepository.countAvailableBySection(1L)).thenReturn(listOf(
                SectionAvailabilityResponse("A", 25L, 30L, 0L),
                SectionAvailabilityResponse("B", 20L, 30L, 0L)
            ))
            Mockito.doReturn(event).`when`(eventRepository).save(any() ?: event)

            val count = eventService.openEvents()

            assertEquals(1, count)
            assertEquals(EventStatus.OPEN, event.status)
            verify(eventCache).saveEvent(eq(1L) ?: 0, any() ?: emptyMap())
            verify(dynamicScheduler).startProcessing(1L)
        }

        @Test
        @DisplayName("SEAT_PICK 이벤트를 OPEN하면 좌석 캐시도 생성한다")
        fun openEventsSeatPick() {
            val event = Event(
                id = 1L, name = "Concert",
                eventTime = LocalDateTime.of(2026, 5, 1, 19, 0),
                status = EventStatus.CLOSED,
                ticketOpenTime = LocalDateTime.of(2026, 4, 1, 10, 0),
                ticketCloseTime = LocalDateTime.of(2026, 4, 30, 23, 59),
                seatSelectionType = SeatSelectionType.SEAT_PICK
            )
            Mockito.doReturn(listOf(event)).`when`(eventRepository)
                .findEventsToOpen(any() ?: EventStatus.CLOSED, any() ?: LocalDateTime.MIN)
            `when`(seatRepository.countAvailableSeats(1L)).thenReturn(50L)
            `when`(seatQueryRepository.countAvailableBySection(1L)).thenReturn(listOf(
                SectionAvailabilityResponse("A", 25L, 30L, 0L)
            ))
            Mockito.doReturn(event).`when`(eventRepository).save(any() ?: event)

            val seat1 = Seat(id = 10L, event = event, seatNumber = "A-1", section = "A", status = SeatStatus.AVAILABLE)
            val seat2 = Seat(id = 11L, event = event, seatNumber = "A-2", section = "A", status = SeatStatus.RESERVED)
            `when`(seatRepository.findByEventId(1L)).thenReturn(listOf(seat1, seat2))

            val count = eventService.openEvents()

            assertEquals(1, count)
            verify(eventCache).saveAllSeats(eq(1L) ?: 0, Mockito.argThat<Map<String, String>> { map ->
                map?.get("10") == "A:A-1:AVAILABLE" && map?.get("11") == "A:A-2:RESERVED"
            } ?: emptyMap())
        }

        @Test
        @DisplayName("조건에 맞는 이벤트가 없으면 0을 반환한다")
        fun noEventsToOpen() {
            Mockito.doReturn(emptyList<Event>()).`when`(eventRepository)
                .findEventsToOpen(any() ?: EventStatus.CLOSED, any() ?: LocalDateTime.MIN)

            assertEquals(0, eventService.openEvents())
        }
    }

    @Nested
    @DisplayName("closeEvents - 이벤트 닫기")
    inner class CloseEvents {

        @Test
        @DisplayName("종료 시간이 지난 이벤트를 CLOSED로 변경하고 캐시를 삭제한다")
        fun closesExpiredEvents() {
            val event = Event(
                id = 1L, name = "Concert",
                eventTime = LocalDateTime.of(2026, 5, 1, 19, 0),
                status = EventStatus.OPEN,
                ticketOpenTime = LocalDateTime.of(2026, 4, 1, 10, 0),
                ticketCloseTime = LocalDateTime.of(2026, 4, 8, 23, 59)
            )
            Mockito.doReturn(listOf(event)).`when`(eventRepository)
                .findEventsToClose(any() ?: EventStatus.OPEN, any() ?: LocalDateTime.MIN)
            Mockito.doReturn(event).`when`(eventRepository).save(any() ?: event)

            val count = eventService.closeEvents()

            assertEquals(1, count)
            assertEquals(EventStatus.CLOSED, event.status)
            verify(eventCache).deleteEvent(1L)
            verify(eventCache).deleteSeatCache(1L)
            verify(dynamicScheduler).stopProcessing(1L)
        }
    }

    @Nested
    @DisplayName("warmupCache - 시작 시 캐시 워밍업")
    inner class WarmupCache {

        @Test
        @DisplayName("OPEN 이벤트를 캐시에 올리고 스케줄러를 등록한다")
        fun warmsUpOpenEvents() {
            val event = Event(
                id = 1L, name = "Concert",
                eventTime = LocalDateTime.of(2026, 5, 1, 19, 0),
                status = EventStatus.OPEN,
                ticketOpenTime = LocalDateTime.of(2026, 4, 1, 10, 0),
                ticketCloseTime = LocalDateTime.of(2026, 4, 30, 23, 59)
            )
            `when`(eventRepository.findByStatus(EventStatus.OPEN)).thenReturn(listOf(event))
            `when`(seatRepository.countAvailableSeats(1L)).thenReturn(50L)
            `when`(seatQueryRepository.countAvailableBySection(1L)).thenReturn(listOf(
                SectionAvailabilityResponse("A", 25L, 30L, 0L)
            ))

            val count = eventService.warmupCache()

            assertEquals(1, count)
            verify(eventCache).saveEvent(eq(1L) ?: 0, any() ?: emptyMap())
            verify(dynamicScheduler).startProcessing(1L)
        }

        @Test
        @DisplayName("OPEN 이벤트가 없으면 0을 반환한다")
        fun noOpenEvents() {
            `when`(eventRepository.findByStatus(EventStatus.OPEN)).thenReturn(emptyList())

            assertEquals(0, eventService.warmupCache())
            verify(eventCache, never()).saveEvent(anyLong(), any() ?: emptyMap())
        }
    }

    @Nested
    @DisplayName("isEventOpen - 이벤트 열림 여부 확인")
    inner class IsEventOpen {

        @Test
        @DisplayName("캐시에 있으면 true를 반환한다")
        fun eventIsOpen() {
            `when`(eventCache.exists(1L)).thenReturn(true)
            assertTrue(eventService.isEventOpen(1L))
        }

        @Test
        @DisplayName("캐시에 없으면 false를 반환한다")
        fun eventIsNotOpen() {
            `when`(eventCache.exists(1L)).thenReturn(false)
            assertFalse(eventService.isEventOpen(1L))
        }
    }

    @Nested
    @DisplayName("syncAllRemainingSeats - 잔여 좌석 동기화")
    inner class SyncAllRemainingSeats {

        @Test
        @DisplayName("OPEN 이벤트의 잔여 좌석 수를 캐시에 동기화한다")
        fun syncsRemainingSeatsForOpenEvents() {
            val event = Event(
                id = 1L, name = "Concert",
                eventTime = LocalDateTime.of(2026, 5, 1, 19, 0),
                status = EventStatus.OPEN
            )
            `when`(eventRepository.findByStatus(EventStatus.OPEN)).thenReturn(listOf(event))
            `when`(seatRepository.countAvailableSeats(1L)).thenReturn(42L)
            `when`(seatQueryRepository.countAvailableBySection(1L)).thenReturn(listOf(
                SectionAvailabilityResponse("A", 25L, 30L, 0L),
                SectionAvailabilityResponse("B", 17L, 30L, 0L)
            ))

            val count = eventService.syncAllRemainingSeats()

            assertEquals(1, count)
            verify(eventCache).setField(1L, "remainingSeats", "42")
            verify(eventCache).setField(1L, sectionAvailableField("A"), "25")
            verify(eventCache).setField(1L, sectionAvailableField("B"), "17")
            verify(dynamicScheduler).stopProcessing(1L)
            verify(dynamicScheduler).startProcessing(1L)
        }

        @Test
        @DisplayName("SEAT_PICK 이벤트 동기화 시 좌석 캐시를 다시 생성한다")
        fun syncSeatPickRebuildsSeatCache() {
            val event = Event(
                id = 1L, name = "Concert",
                eventTime = LocalDateTime.of(2026, 5, 1, 19, 0),
                status = EventStatus.OPEN,
                seatSelectionType = SeatSelectionType.SEAT_PICK
            )
            `when`(eventRepository.findByStatus(EventStatus.OPEN)).thenReturn(listOf(event))
            `when`(seatRepository.countAvailableSeats(1L)).thenReturn(42L)
            `when`(seatQueryRepository.countAvailableBySection(1L)).thenReturn(listOf(
                SectionAvailabilityResponse("A", 25L, 30L, 0L)
            ))
            val seat1 = Seat(id = 10L, event = event, seatNumber = "A-1", section = "A", status = SeatStatus.AVAILABLE)
            val seat2 = Seat(id = 11L, event = event, seatNumber = "A-2", section = "A", status = SeatStatus.RESERVED)
            `when`(seatRepository.findByEventId(1L)).thenReturn(listOf(seat1, seat2))

            eventService.syncAllRemainingSeats()

            verify(eventCache).saveAllSeats(eq(1L) ?: 0, Mockito.argThat<Map<String, String>> { map ->
                map?.get("10") == "A:A-1:AVAILABLE" && map?.get("11") == "A:A-2:RESERVED"
            } ?: emptyMap())
        }

        @Test
        @DisplayName("동기화 시 stop → sync → start 순서로 처리한다")
        fun syncStopsAndRestartsProcessingPerEvent() {
            val event = Event(
                id = 1L, name = "Concert",
                eventTime = LocalDateTime.of(2026, 5, 1, 19, 0),
                status = EventStatus.OPEN
            )
            `when`(eventRepository.findByStatus(EventStatus.OPEN)).thenReturn(listOf(event))
            `when`(seatRepository.countAvailableSeats(1L)).thenReturn(42L)
            `when`(seatQueryRepository.countAvailableBySection(1L)).thenReturn(listOf(
                SectionAvailabilityResponse("A", 25L, 30L, 0L)
            ))

            eventService.syncAllRemainingSeats()

            val inOrder = inOrder(dynamicScheduler, seatRepository, eventCache)
            inOrder.verify(dynamicScheduler).stopProcessing(1L)
            inOrder.verify(seatRepository).countAvailableSeats(1L)
            inOrder.verify(eventCache).setField(1L, "remainingSeats", "42")
            inOrder.verify(dynamicScheduler).startProcessing(1L)
        }
    }
}

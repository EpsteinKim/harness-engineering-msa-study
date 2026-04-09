package com.epstein.practice.reserveservice.service

import com.epstein.practice.reserveservice.constant.eventCacheKey
import com.epstein.practice.reserveservice.constant.seatCacheKey
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
import org.mockito.Mockito.lenient
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
class EventServiceTest {

    @Mock
    lateinit var eventRepository: EventRepository

    @Mock
    lateinit var seatRepository: SeatRepository

    @Mock
    lateinit var seatQueryRepository: SeatQueryRepository

    @Mock
    lateinit var redis: StringRedisTemplate

    @Mock
    lateinit var dynamicScheduler: DynamicScheduler

    @Mock
    lateinit var hashOps: HashOperations<String, String, String>

    private lateinit var eventService: EventService

    @BeforeEach
    fun setUp() {
        lenient().`when`(redis.opsForHash<String, String>()).thenReturn(hashOps)
        eventService = EventService(eventRepository, seatRepository, seatQueryRepository, redis, dynamicScheduler)
    }

    @Nested
    @DisplayName("openEvents - 이벤트 열기")
    inner class OpenEvents {

        @Test
        @DisplayName("조건에 맞는 이벤트를 OPEN으로 변경하고 Redis에 캐싱한다")
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
                SectionAvailabilityResponse("A", 25L, 30L),
                SectionAvailabilityResponse("B", 20L, 30L)
            ))
            Mockito.doReturn(event).`when`(eventRepository).save(any() ?: event)
            `when`(redis.expire(eq("event:1"), any())).thenReturn(true)

            val count = eventService.openEvents()

            assertEquals(1, count)
            assertEquals(EventStatus.OPEN, event.status)
            verify(hashOps).putAll(eq("event:1"), any() ?: emptyMap())
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
                SectionAvailabilityResponse("A", 25L, 30L)
            ))
            Mockito.doReturn(event).`when`(eventRepository).save(any() ?: event)
            `when`(redis.expire(eq("event:1"), any())).thenReturn(true)
            `when`(redis.expire(eq("event:1:seats"), any())).thenReturn(true)

            val seat1 = Seat(id = 10L, event = event, seatNumber = "A-1", section = "A", status = SeatStatus.AVAILABLE)
            val seat2 = Seat(id = 11L, event = event, seatNumber = "A-2", section = "A", status = SeatStatus.RESERVED)
            `when`(seatRepository.findByEventId(1L)).thenReturn(listOf(seat1, seat2))

            val count = eventService.openEvents()

            assertEquals(1, count)
            verify(hashOps).putAll(eq("event:1:seats"), Mockito.argThat { map ->
                map["10"] == "A-1:A:AVAILABLE" && map["11"] == "A-2:A:RESERVED"
            })
        }

        @Test
        @DisplayName("조건에 맞는 이벤트가 없으면 0을 반환한다")
        fun noEventsToOpen() {
            Mockito.doReturn(emptyList<Event>()).`when`(eventRepository)
                .findEventsToOpen(any() ?: EventStatus.CLOSED, any() ?: LocalDateTime.MIN)

            assertEquals(0, eventService.openEvents())
            verify(eventRepository, never()).save(any() ?: Event(name = "", eventTime = LocalDateTime.MIN))
        }
    }

    @Nested
    @DisplayName("closeEvents - 이벤트 닫기")
    inner class CloseEvents {

        @Test
        @DisplayName("종료 시간이 지난 이벤트를 CLOSED로 변경하고 Redis 캐시를 삭제한다")
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
            verify(redis).delete("event:1")
            verify(redis).delete("event:1:seats")
            verify(dynamicScheduler).stopProcessing(1L)
        }

        @Test
        @DisplayName("닫을 이벤트가 없으면 0을 반환한다")
        fun noEventsToClose() {
            Mockito.doReturn(emptyList<Event>()).`when`(eventRepository)
                .findEventsToClose(any() ?: EventStatus.OPEN, any() ?: LocalDateTime.MIN)

            assertEquals(0, eventService.closeEvents())
            verify(redis, never()).delete(anyString())
        }
    }

    @Nested
    @DisplayName("isEventOpen - 이벤트 열림 여부 확인")
    inner class IsEventOpen {

        @Test
        @DisplayName("Redis에 캐시가 있으면 true를 반환한다")
        fun eventIsOpen() {
            `when`(redis.hasKey("event:1")).thenReturn(true)

            assertTrue(eventService.isEventOpen(1L))
        }

        @Test
        @DisplayName("Redis에 캐시가 없으면 false를 반환한다")
        fun eventIsNotOpen() {
            `when`(redis.hasKey("event:1")).thenReturn(false)

            assertFalse(eventService.isEventOpen(1L))
        }
    }

    @Nested
    @DisplayName("getOpenEventIds - 열린 이벤트 ID 목록 조회")
    inner class GetOpenEventIds {

        @Test
        @DisplayName("OPEN 상태의 이벤트 ID 목록을 반환한다")
        fun returnsOpenEventIds() {
            val events = listOf(
                Event(
                    id = 1L, name = "Concert",
                    eventTime = LocalDateTime.of(2026, 5, 1, 19, 0),
                    status = EventStatus.OPEN,
                    ticketOpenTime = LocalDateTime.of(2026, 4, 1, 10, 0),
                    ticketCloseTime = LocalDateTime.of(2026, 4, 30, 23, 59)
                ),
                Event(
                    id = 2L, name = "Festival",
                    eventTime = LocalDateTime.of(2026, 6, 1, 18, 0),
                    status = EventStatus.OPEN,
                    ticketOpenTime = LocalDateTime.of(2026, 5, 1, 10, 0),
                    ticketCloseTime = LocalDateTime.of(2026, 5, 31, 23, 59)
                )
            )
            `when`(eventRepository.findByStatus(EventStatus.OPEN)).thenReturn(events)

            val result = eventService.getOpenEventIds()

            assertEquals(listOf(1L, 2L), result)
        }

        @Test
        @DisplayName("OPEN 상태의 이벤트가 없으면 빈 리스트를 반환한다")
        fun returnsEmptyListWhenNoOpenEvents() {
            `when`(eventRepository.findByStatus(EventStatus.OPEN)).thenReturn(emptyList())

            val result = eventService.getOpenEventIds()

            assertTrue(result.isEmpty())
        }
    }

    @Nested
    @DisplayName("syncAllRemainingSeats - 잔여 좌석 동기화")
    inner class SyncAllRemainingSeats {

        @Test
        @DisplayName("OPEN 이벤트의 잔여 좌석 수를 Redis에 동기화한다")
        fun syncsRemainingSeatsForOpenEvents() {
            val event = Event(
                id = 1L, name = "Concert",
                eventTime = LocalDateTime.of(2026, 5, 1, 19, 0),
                status = EventStatus.OPEN,
                ticketOpenTime = LocalDateTime.of(2026, 4, 1, 10, 0),
                ticketCloseTime = LocalDateTime.of(2026, 4, 30, 23, 59)
            )
            `when`(eventRepository.findByStatus(EventStatus.OPEN)).thenReturn(listOf(event))
            `when`(seatRepository.countAvailableSeats(1L)).thenReturn(42L)
            `when`(seatQueryRepository.countAvailableBySection(1L)).thenReturn(listOf(
                SectionAvailabilityResponse("A", 25L, 30L),
                SectionAvailabilityResponse("B", 17L, 30L)
            ))

            val count = eventService.syncAllRemainingSeats()

            assertEquals(1, count)
            verify(hashOps).put(eventCacheKey(1L), "remainingSeats", "42")
            verify(hashOps).put(eventCacheKey(1L), "section:A:available", "25")
            verify(hashOps).put(eventCacheKey(1L), "section:B:available", "17")
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
                ticketOpenTime = LocalDateTime.of(2026, 4, 1, 10, 0),
                ticketCloseTime = LocalDateTime.of(2026, 4, 30, 23, 59),
                seatSelectionType = SeatSelectionType.SEAT_PICK
            )
            `when`(eventRepository.findByStatus(EventStatus.OPEN)).thenReturn(listOf(event))
            `when`(seatRepository.countAvailableSeats(1L)).thenReturn(42L)
            `when`(seatQueryRepository.countAvailableBySection(1L)).thenReturn(listOf(
                SectionAvailabilityResponse("A", 25L, 30L)
            ))
            val seat1 = Seat(id = 10L, event = event, seatNumber = "A-1", section = "A", status = SeatStatus.AVAILABLE)
            val seat2 = Seat(id = 11L, event = event, seatNumber = "A-2", section = "A", status = SeatStatus.RESERVED)
            `when`(seatRepository.findByEventId(1L)).thenReturn(listOf(seat1, seat2))

            val count = eventService.syncAllRemainingSeats()

            assertEquals(1, count)
            verify(seatRepository).findByEventId(1L)
            verify(hashOps).putAll(eq("event:1:seats"), Mockito.argThat { map ->
                map["10"] == "A-1:A:AVAILABLE" && map["11"] == "A-2:A:RESERVED"
            })
        }

        @Test
        @DisplayName("OPEN 이벤트가 없으면 0을 반환한다")
        fun returnsZeroWhenNoOpenEvents() {
            `when`(eventRepository.findByStatus(EventStatus.OPEN)).thenReturn(emptyList())

            val count = eventService.syncAllRemainingSeats()

            assertEquals(0, count)
            verify(hashOps, never()).put(anyString(), anyString(), anyString())
        }

        @Test
        @DisplayName("동기화 시 이벤트별로 stop 후 DB 조회, Redis 업데이트, start 순서로 처리한다")
        fun syncStopsAndRestartsProcessingPerEvent() {
            val event = Event(
                id = 1L, name = "Concert",
                eventTime = LocalDateTime.of(2026, 5, 1, 19, 0),
                status = EventStatus.OPEN,
                ticketOpenTime = LocalDateTime.of(2026, 4, 1, 10, 0),
                ticketCloseTime = LocalDateTime.of(2026, 4, 30, 23, 59)
            )
            `when`(eventRepository.findByStatus(EventStatus.OPEN)).thenReturn(listOf(event))
            `when`(seatRepository.countAvailableSeats(1L)).thenReturn(42L)
            `when`(seatQueryRepository.countAvailableBySection(1L)).thenReturn(listOf(
                SectionAvailabilityResponse("A", 25L, 30L)
            ))

            eventService.syncAllRemainingSeats()

            val inOrder = inOrder(dynamicScheduler, seatRepository, hashOps)
            inOrder.verify(dynamicScheduler).stopProcessing(1L)
            inOrder.verify(seatRepository).countAvailableSeats(1L)
            inOrder.verify(hashOps).put(eventCacheKey(1L), "remainingSeats", "42")
            inOrder.verify(dynamicScheduler).startProcessing(1L)
        }
    }
}

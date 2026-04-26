package com.epstein.practice.reserveservice.main.service

import com.epstein.practice.common.exception.ServerException
import com.epstein.practice.reserveservice.main.cache.EventCacheRepository
import com.epstein.practice.reserveservice.main.cache.QueueCacheRepository
import com.epstein.practice.reserveservice.main.cache.QueueCacheRepository.EnqueueValidation
import com.epstein.practice.reserveservice.main.client.PaymentClient
import com.epstein.practice.reserveservice.main.client.PaymentSummary
import com.epstein.practice.reserveservice.main.client.UserClient
import com.epstein.practice.reserveservice.type.entity.Seat
import com.epstein.practice.reserveservice.type.entity.SeatStatus
import com.epstein.practice.reserveservice.main.repository.SeatRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mock
import org.mockito.Mockito.lenient
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.time.ZonedDateTime

@ExtendWith(MockitoExtension::class)
class ReservationServiceTest {

    @Mock
    lateinit var eventCache: EventCacheRepository

    @Mock
    lateinit var queueCache: QueueCacheRepository

    @Mock
    lateinit var seatService: SeatService

    @Mock
    lateinit var seatRepository: SeatRepository

    @Mock
    lateinit var userClient: UserClient

    @Mock
    lateinit var paymentClient: PaymentClient

    @Mock
    lateinit var sagaOrchestrator: SagaOrchestrator

    private lateinit var service: ReservationService

    @BeforeEach
    fun setUp() {
        service = ReservationService(
            eventCache, queueCache, seatService, seatRepository,
            userClient, paymentClient, sagaOrchestrator
        )
        lenient().`when`(userClient.exists(anyLong())).thenReturn(true)
        lenient().`when`(seatRepository.existsByEventIdAndUserIdAndStatusIn(
            anyLong(), anyLong(), anyList()
        )).thenReturn(false)
    }

    @Nested
    @DisplayName("enqueue - 대기열에 추가")
    inner class Enqueue {

        @Test
        @DisplayName("이벤트가 열려있으면 좌석 ID로 대기열에 추가한다")
        fun enqueueBySeatId() {
            `when`(queueCache.validateEnqueue(1L, "1"))
                .thenReturn(EnqueueValidation(true, false, "SEAT_PICK"))
            `when`(eventCache.tryHoldSeat(anyLong(), anyLong(), anyString(), anyLong(), anyLong()))
                .thenReturn(true)
            `when`(queueCache.enqueue(anyLong(), anyString(), anyLong(), isNull()))
                .thenReturn(1L)

            service.enqueue("1", 1L, seatId = 10L)

            verify(queueCache).enqueue(anyLong(), anyString(), anyLong(), isNull())
            verify(queueCache).holdSeat(1L, "1", 10L)
        }

        @Test
        @DisplayName("이벤트가 열려있으면 구역으로 대기열에 추가한다")
        fun enqueueBySection() {
            `when`(queueCache.validateEnqueue(1L, "1"))
                .thenReturn(EnqueueValidation(true, false, "SECTION_SELECT"))
            `when`(queueCache.enqueue(anyLong(), anyString(), isNull(), anyString()))
                .thenReturn(1L)

            service.enqueue("1", 1L, section = "A")

            verify(queueCache).enqueue(anyLong(), anyString(), isNull(), anyString())
        }

        @Test
        @DisplayName("Lua 스크립트에서 섹션 매진 반환 시 SECTION_FULL 예외")
        fun enqueueSectionFull() {
            `when`(queueCache.validateEnqueue(1L, "1"))
                .thenReturn(EnqueueValidation(true, false, "SECTION_SELECT"))
            `when`(queueCache.enqueue(anyLong(), anyString(), isNull(), anyString()))
                .thenReturn(-2L)

            val exception = assertThrows(ServerException::class.java) {
                service.enqueue("1", 1L, section = "A")
            }
            assertEquals("SECTION_FULL", exception.code)
        }

        @Test
        @DisplayName("SEAT_PICK에서 이미 대기열에 있으면 tryHoldSeat가 호출되지 않고 ALREADY_IN_QUEUE 예외")
        fun enqueueSeatPickAlreadyInQueueSkipsHold() {
            `when`(queueCache.validateEnqueue(1L, "1"))
                .thenReturn(EnqueueValidation(true, true, "SEAT_PICK"))

            val exception = assertThrows(ServerException::class.java) {
                service.enqueue("1", 1L, seatId = 10L)
            }
            assertEquals("ALREADY_IN_QUEUE", exception.code)
            verify(eventCache, never()).tryHoldSeat(anyLong(), anyLong(), anyString(), anyLong(), anyLong())
        }

        @Test
        @DisplayName("Lua 스크립트에서 잔여석 없음 반환 시 NO_REMAINING_SEATS 예외")
        fun enqueueNoRemainingSeats() {
            `when`(queueCache.validateEnqueue(1L, "1"))
                .thenReturn(EnqueueValidation(true, false, "SEAT_PICK"))
            `when`(eventCache.tryHoldSeat(anyLong(), anyLong(), anyString(), anyLong(), anyLong()))
                .thenReturn(true)
            `when`(queueCache.enqueue(anyLong(), anyString(), anyLong(), isNull()))
                .thenReturn(-1L)

            val exception = assertThrows(ServerException::class.java) {
                service.enqueue("1", 1L, seatId = 10L)
            }
            assertEquals("잔여 좌석이 없습니다", exception.message)
            assertEquals("NO_REMAINING_SEATS", exception.code)
        }

        @Test
        @DisplayName("이벤트가 열리지 않았으면 EVENT_NOT_OPEN 예외")
        fun enqueueEventNotOpen() {
            `when`(queueCache.validateEnqueue(1L, "1"))
                .thenReturn(EnqueueValidation(false, false, ""))

            val exception = assertThrows(ServerException::class.java) {
                service.enqueue("1", 1L, seatId = 10L)
            }
            assertEquals("이벤트가 예약 가능한 상태가 아닙니다", exception.message)
            assertEquals("EVENT_NOT_OPEN", exception.code)
        }

        @Test
        @DisplayName("SEAT_PICK 이벤트에 seatId 없이 요청하면 INVALID_REQUEST 예외")
        fun enqueueSeatPickWithoutSeatId() {
            `when`(queueCache.validateEnqueue(1L, "1"))
                .thenReturn(EnqueueValidation(true, false, "SEAT_PICK"))

            val exception = assertThrows(ServerException::class.java) {
                service.enqueue("1", 1L, section = "A")
            }
            assertEquals("INVALID_REQUEST", exception.code)
        }

        @Test
        @DisplayName("SECTION_SELECT 이벤트에 section 없이 요청하면 INVALID_REQUEST 예외")
        fun enqueueSectionSelectWithoutSection() {
            `when`(queueCache.validateEnqueue(1L, "1"))
                .thenReturn(EnqueueValidation(true, false, "SECTION_SELECT"))

            val exception = assertThrows(ServerException::class.java) {
                service.enqueue("1", 1L, seatId = 10L)
            }
            assertEquals("INVALID_REQUEST", exception.code)
        }

        @Test
        @DisplayName("HOLD 획득 실패 시 SEAT_UNAVAILABLE 예외")
        fun enqueueSeatAlreadyReserved() {
            `when`(queueCache.validateEnqueue(1L, "1"))
                .thenReturn(EnqueueValidation(true, false, "SEAT_PICK"))
            `when`(eventCache.tryHoldSeat(anyLong(), anyLong(), anyString(), anyLong(), anyLong()))
                .thenReturn(false)

            val exception = assertThrows(ServerException::class.java) {
                service.enqueue("1", 1L, seatId = 10L)
            }
            assertEquals("SEAT_UNAVAILABLE", exception.code)
            verify(queueCache, never()).enqueue(anyLong(), anyString(), anyLong(), anyString())
        }

        @Test
        @DisplayName("이미 대기열에 있는 유저는 ALREADY_IN_QUEUE 예외")
        fun enqueueAlreadyInQueue() {
            `when`(queueCache.validateEnqueue(1L, "1"))
                .thenReturn(EnqueueValidation(true, true, "SECTION_SELECT"))

            val exception = assertThrows(ServerException::class.java) {
                service.enqueue("1", 1L, section = "A")
            }
            assertEquals("ALREADY_IN_QUEUE", exception.code)
        }

        @Test
        @DisplayName("이미 해당 이벤트에 예약이 존재하면 ALREADY_RESERVED 예외")
        fun enqueueAlreadyReserved() {
            `when`(queueCache.validateEnqueue(1L, "1"))
                .thenReturn(EnqueueValidation(true, false, "SEAT_PICK"))
            `when`(seatRepository.existsByEventIdAndUserIdAndStatusIn(
                eq(1L), eq(1L), anyList()
            )).thenReturn(true)

            val exception = assertThrows(ServerException::class.java) {
                service.enqueue("1", 1L, seatId = 10L)
            }
            assertEquals("ALREADY_RESERVED", exception.code)
            verify(queueCache, never()).enqueue(anyLong(), anyString(), anyLong(), isNull())
        }

        @Test
        @DisplayName("userId가 숫자가 아니면 USER_NOT_FOUND 예외")
        fun enqueueInvalidUserIdFormat() {
            val exception = assertThrows(ServerException::class.java) {
                service.enqueue("not-a-number", 1L, seatId = 10L)
            }
            assertEquals("USER_NOT_FOUND", exception.code)
        }
    }

    @Nested
    @DisplayName("removeFromWaiting - 대기열에서 제거")
    inner class RemoveFromWaiting {

        @Test
        @DisplayName("대기열에서 유저를 제거하고 hold를 해제한다")
        fun removeFromWaitingSuccess() {
            service.removeFromWaiting(1L, "1")
            verify(queueCache).removeFromQueue(1L, "1")
            verify(queueCache).releaseHeldSeat(1L, "1")
        }
    }

    @Nested
    @DisplayName("cancel - 예약 취소")
    inner class Cancel {

        @Test
        @DisplayName("활성 Saga가 있으면 onCancel 호출 후 true 반환")
        fun cancelWithActiveSaga() {
            `when`(queueCache.getDispatchData(1L, "1")).thenReturn(null to "A")
            `when`(queueCache.removeFromQueue(1L, "1")).thenReturn(1L)
            `when`(sagaOrchestrator.findActiveSaga(1L, 1L))
                .thenReturn(com.epstein.practice.reserveservice.type.entity.ReservationSaga(
                    id = 1L, eventId = 1L, userId = 1L, seatId = 10L,
                    step = com.epstein.practice.reserveservice.type.constant.SagaStep.PAYMENT_CREATED,
                    status = com.epstein.practice.reserveservice.type.constant.SagaStatus.IN_PROGRESS,
                ))

            assertTrue(service.cancel(1L, "1"))
            verify(sagaOrchestrator).onCancel(1L)
            verify(eventCache).adjustSeatCounts(1L, 1L, "A")
        }

        @Test
        @DisplayName("활성 Saga가 없고 큐에서만 제거되면 true 반환")
        fun cancelFromQueueOnly() {
            `when`(queueCache.getDispatchData(1L, "1")).thenReturn(null to "A")
            `when`(queueCache.removeFromQueue(1L, "1")).thenReturn(1L)
            `when`(sagaOrchestrator.findActiveSaga(1L, 1L)).thenReturn(null)

            assertTrue(service.cancel(1L, "1"))
            verify(sagaOrchestrator, never()).onCancel(anyLong())
            verify(eventCache).adjustSeatCounts(1L, 1L, "A")
        }

        @Test
        @DisplayName("큐에도 없고 Saga도 없으면 false 반환")
        fun cancelNotFound() {
            `when`(queueCache.getDispatchData(1L, "1")).thenReturn(null to null)
            `when`(queueCache.removeFromQueue(1L, "1")).thenReturn(0L)
            `when`(sagaOrchestrator.findActiveSaga(1L, 1L)).thenReturn(null)

            assertFalse(service.cancel(1L, "1"))
            verify(eventCache, never()).adjustSeatCounts(anyLong(), anyLong(), anyString())
        }

        @Test
        @DisplayName("hold된 좌석이 있으면 releaseHold가 호출된다")
        fun cancelReleasesHoldWhenSeatIdPresent() {
            `when`(queueCache.getHeldSeatId(1L, "1")).thenReturn(10L)
            `when`(queueCache.getDispatchData(1L, "1")).thenReturn(null to "A")
            `when`(queueCache.removeFromQueue(1L, "1")).thenReturn(1L)
            `when`(sagaOrchestrator.findActiveSaga(1L, 1L)).thenReturn(null)

            service.cancel(1L, "1")

            verify(eventCache).releaseHold(1L, 10L, "1")
            verify(eventCache).adjustSeatCounts(1L, 1L, "A")
        }

        @Test
        @DisplayName("hold된 좌석이 없으면 releaseHold가 호출되지 않는다")
        fun cancelNoReleaseHoldWhenNoSeatId() {
            `when`(queueCache.getHeldSeatId(1L, "1")).thenReturn(null)
            `when`(queueCache.getDispatchData(1L, "1")).thenReturn(null to "A")
            `when`(queueCache.removeFromQueue(1L, "1")).thenReturn(1L)
            `when`(sagaOrchestrator.findActiveSaga(1L, 1L)).thenReturn(null)

            service.cancel(1L, "1")

            verify(eventCache, never()).releaseHold(anyLong(), anyLong(), anyString())
            verify(eventCache).adjustSeatCounts(1L, 1L, "A")
        }
    }

    @Nested
    @DisplayName("getPosition - 대기열 위치 조회")
    inner class GetPosition {

        @Test
        @DisplayName("대기열에 있는 유저의 위치를 반환한다")
        fun getPositionFound() {
            `when`(queueCache.getQueuePosition(1L, "1")).thenReturn(3L)
            assertEquals(3L, service.getPosition(1L, "1"))
        }

        @Test
        @DisplayName("대기열에 없는 유저면 null을 반환한다")
        fun getPositionNotFound() {
            `when`(queueCache.getQueuePosition(1L, "1")).thenReturn(null)
            assertNull(service.getPosition(1L, "1"))
        }
    }

    @Nested
    @DisplayName("getMyReservations - 사용자 예약 이력")
    inner class GetMyReservations {

        @Test
        @DisplayName("좌석과 결제 정보 조합해 반환")
        fun withPayment() {
            val seat = Seat(
                id = 10L, eventId = 1L, seatNumber = "A-1", section = "A",
                status = SeatStatus.RESERVED, userId = 1L, priceAmount = 200000L,
                reservedAt = ZonedDateTime.of(2026, 4, 10, 9, 0, 0, 0, java.time.ZoneOffset.UTC)
            )
            `when`(eventCache.getAllFields(1L)).thenReturn(mapOf(
                "name" to "Concert",
                "eventTime" to "2026-05-01T19:00"
            ))
            `when`(seatRepository.findActiveByUserId(1L)).thenReturn(listOf(seat))
            `when`(paymentClient.listByUser(1L)).thenReturn(
                listOf(PaymentSummary(id = 100L, seatId = 10L, status = "SUCCEEDED"))
            )

            val result = service.getMyReservations(1L)

            assertEquals(1, result.size)
            assertEquals(10L, result[0].seatId)
            assertEquals("RESERVED", result[0].seatStatus)
            assertEquals(100L, result[0].paymentId)
            assertEquals("SUCCEEDED", result[0].paymentStatus)
            assertEquals(200000L, result[0].priceAmount)
        }

        @Test
        @DisplayName("좌석 없으면 payment-service 호출 없이 빈 리스트")
        fun noSeats() {
            `when`(seatRepository.findActiveByUserId(1L)).thenReturn(emptyList())

            val result = service.getMyReservations(1L)

            assertTrue(result.isEmpty())
            verify(paymentClient, never()).listByUser(anyLong())
        }

        @Test
        @DisplayName("결제 정보 없으면 paymentId/paymentStatus null")
        fun noPayment() {
            val seat = Seat(
                id = 10L, eventId = 1L, seatNumber = "A-1", section = "A",
                status = SeatStatus.PAYMENT_PENDING, userId = 1L, priceAmount = 200000L
            )
            `when`(eventCache.getAllFields(1L)).thenReturn(mapOf(
                "name" to "Concert",
                "eventTime" to "2026-05-01T19:00"
            ))
            `when`(seatRepository.findActiveByUserId(1L)).thenReturn(listOf(seat))
            `when`(paymentClient.listByUser(1L)).thenReturn(emptyList())

            val result = service.getMyReservations(1L)

            assertEquals(1, result.size)
            assertEquals("PAYMENT_PENDING", result[0].seatStatus)
            assertNull(result[0].paymentId)
            assertNull(result[0].paymentStatus)
        }
    }
}

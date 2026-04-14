package com.epstein.practice.reserveservice.service

import com.epstein.practice.common.exception.ServerException
import com.epstein.practice.reserveservice.cache.EventCacheRepository
import com.epstein.practice.reserveservice.client.PaymentClient
import com.epstein.practice.reserveservice.client.PaymentProcessRequest
import com.epstein.practice.reserveservice.constant.ErrorCode
import com.epstein.practice.reserveservice.entity.EventStatus
import com.epstein.practice.reserveservice.entity.SeatStatus
import com.epstein.practice.reserveservice.repository.EventRepository
import com.epstein.practice.reserveservice.repository.SeatRepository
import com.epstein.practice.reserveservice.scheduler.DynamicScheduler
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class PaymentOrchestrator(
    private val seatRepository: SeatRepository,
    private val paymentClient: PaymentClient,
    private val eventCache: EventCacheRepository,
    private val eventRepository: EventRepository,
    @Lazy private val dynamicScheduler: DynamicScheduler,
) {
    private val logger = LoggerFactory.getLogger(PaymentOrchestrator::class.java)

    @Transactional
    fun pay(eventId: Long, userId: Long, amount: Long, method: String): PaymentOrchestrationResult {
        val seat = seatRepository.findByEventIdAndUserIdAndStatus(eventId, userId, SeatStatus.PAYMENT_PENDING)
            ?: throw ServerException(
                message = "결제 대기 중인 좌석이 없습니다",
                code = ErrorCode.PAYMENT_PENDING_NOT_FOUND
            )

        val result = paymentClient.processPayment(
            PaymentProcessRequest(
                userId = userId,
                seatId = seat.id,
                eventId = eventId,
                amount = amount,
                method = method
            )
        )

        return if (result.success) {
            seat.status = SeatStatus.RESERVED
            seat.reservedAt = LocalDateTime.now()
            logger.info("Payment succeeded: user={}, seat={}, paymentId={}", userId, seat.id, result.paymentId)
            PaymentOrchestrationResult(
                success = true,
                seatId = seat.id,
                paymentId = result.paymentId,
                message = "결제가 완료되어 예약이 확정되었습니다"
            )
        } else {
            // Saga 보상: 좌석을 AVAILABLE로 복구 + 캐시 갱신 + 스케줄러 재시작
            val section = seat.section
            seat.status = SeatStatus.AVAILABLE
            seat.userId = null
            seat.reservedAt = null
            eventCache.adjustSeatCounts(eventId, 1, section)
            eventCache.markSeatAvailable(eventId, seat.id)
            if (isTicketingStillOpen(eventId)) {
                dynamicScheduler.startProcessing(eventId)
            }
            logger.info("Payment failed, compensated: user={}, seat={}, reason={}",
                userId, seat.id, result.message)
            PaymentOrchestrationResult(
                success = false,
                seatId = seat.id,
                paymentId = result.paymentId,
                message = "결제에 실패하여 좌석 예약이 취소되었습니다"
            )
        }
    }

    private fun isTicketingStillOpen(eventId: Long): Boolean {
        val event = eventRepository.findById(eventId).orElse(null) ?: return false
        if (event.status != EventStatus.OPEN) return false
        val closeTime = event.ticketCloseTime ?: return true
        return closeTime.isAfter(LocalDateTime.now())
    }
}

data class PaymentOrchestrationResult(
    val success: Boolean,
    val seatId: Long,
    val paymentId: Long? = null,
    val message: String
)

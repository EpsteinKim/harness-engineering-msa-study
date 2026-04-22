package com.epstein.practice.reserveservice.producer

import com.epstein.practice.common.event.PaymentRequested
import com.epstein.practice.common.exception.ServerException
import com.epstein.practice.reserveservice.config.KafkaConfig
import com.epstein.practice.reserveservice.type.constant.ErrorCode
import com.epstein.practice.reserveservice.type.entity.SeatStatus
import com.epstein.practice.reserveservice.main.repository.SeatRepository
import org.slf4j.LoggerFactory
import com.epstein.practice.common.outbox.OutboxService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 사용자가 `/pay`를 누르면 seat 상태 검증 후 `PaymentRequested` 이벤트만 발행한다.
 * 실제 결제 처리·좌석 상태 전이는 Kafka Saga에서 비동기로 일어난다.
 */
@Service
class PaymentInitiator(
    private val seatRepository: SeatRepository,
    private val outboxService: OutboxService,
) {
    private val logger = LoggerFactory.getLogger(PaymentInitiator::class.java)

    @Transactional(readOnly = true)
    fun requestPayment(eventId: Long, userId: Long, method: String): Long {
        val seat = seatRepository.findByEventIdAndUserIdAndStatus(eventId, userId, SeatStatus.PAYMENT_PENDING)
            ?: throw ServerException(
                message = "결제 대기 중인 좌석이 없습니다",
                code = ErrorCode.PAYMENT_PENDING_NOT_FOUND
            )

        val request = PaymentRequested(seatId = seat.id, userId = userId, method = method)
        outboxService.save(KafkaConfig.TOPIC_PAYMENT_EVENTS, seat.id.toString(), request)
        logger.info("PaymentRequested published: seat={}, user={}, method={}", seat.id, userId, method)
        return seat.id
    }
}
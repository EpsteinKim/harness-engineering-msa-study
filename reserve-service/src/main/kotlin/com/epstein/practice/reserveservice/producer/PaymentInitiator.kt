package com.epstein.practice.reserveservice.producer

import com.epstein.practice.common.exception.ServerException
import com.epstein.practice.reserveservice.main.service.SagaOrchestrator
import com.epstein.practice.reserveservice.type.constant.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PaymentInitiator(
    private val sagaOrchestrator: SagaOrchestrator,
) {
    private val logger = LoggerFactory.getLogger(PaymentInitiator::class.java)

    @Transactional
    fun requestPayment(eventId: Long, userId: Long, method: String): PayResult {
        val saga = sagaOrchestrator.findActiveSaga(eventId, userId)
            ?: throw ServerException(
                message = "결제 대기 중인 예약이 없습니다",
                code = ErrorCode.PAYMENT_PENDING_NOT_FOUND
            )

        sagaOrchestrator.requestPayment(saga.id, method)
        logger.info("Payment requested via Saga: sagaId={}, user={}, method={}", saga.id, userId, method)
        return PayResult(seatId = saga.seatId, sagaId = saga.id)
    }

    data class PayResult(val seatId: Long, val sagaId: Long)
}

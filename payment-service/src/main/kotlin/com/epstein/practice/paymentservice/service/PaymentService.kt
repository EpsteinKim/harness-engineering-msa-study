package com.epstein.practice.paymentservice.service

import com.epstein.practice.common.exception.ServerException
import com.epstein.practice.paymentservice.constant.ErrorCode
import com.epstein.practice.paymentservice.entity.Payment
import com.epstein.practice.paymentservice.entity.PaymentStatus
import com.epstein.practice.paymentservice.repository.PaymentRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 조회 + PENDING 생성 전용. 실제 결제 처리는 PaymentProcessingService가 이벤트 구동으로 수행.
 */
@Service
class PaymentService(
    private val paymentRepository: PaymentRepository,
) {
    private val logger = LoggerFactory.getLogger(PaymentService::class.java)

    fun getById(id: Long): Payment =
        paymentRepository.findById(id).orElseThrow {
            ServerException(message = "결제 정보를 찾을 수 없습니다", code = ErrorCode.PAYMENT_NOT_FOUND)
        }

    fun getByUserId(userId: Long): List<Payment> =
        paymentRepository.findByUserIdOrderByCreatedAtDesc(userId)

    /**
     * 좌석 HOLD 이벤트(SeatHeld)를 받아 Payment(PENDING)를 생성한다.
     * 멱등성: 동일 seatId에 대해 PENDING이 이미 있으면 skip.
     */
    @Transactional
    fun createPendingForSeat(seatId: Long, userId: Long, eventId: Long, amount: Long): Payment {
        paymentRepository.findBySeatIdAndStatus(seatId, PaymentStatus.PENDING)?.let { existing ->
            logger.info("Pending payment already exists for seat={}, skipping", seatId)
            return existing
        }

        val payment = paymentRepository.save(
            Payment(
                seatId = seatId,
                userId = userId,
                eventId = eventId,
                amount = amount,
                method = null,
                status = PaymentStatus.PENDING
            )
        )
        logger.info("Pending payment created: id={}, seatId={}, userId={}, amount={}",
            payment.id, seatId, userId, amount)
        return payment
    }
}

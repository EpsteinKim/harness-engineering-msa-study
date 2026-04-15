package com.epstein.practice.paymentservice.service

import com.epstein.practice.common.exception.ServerException
import com.epstein.practice.paymentservice.constant.ErrorCode
import com.epstein.practice.paymentservice.constant.PaymentMethod
import com.epstein.practice.paymentservice.dto.PaymentRequest
import com.epstein.practice.paymentservice.entity.Payment
import com.epstein.practice.paymentservice.entity.PaymentStatus
import com.epstein.practice.paymentservice.repository.PaymentRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import kotlin.random.Random

@Service
class PaymentService(
    private val paymentRepository: PaymentRepository,
    @Value("\${payment.success-rate:0.7}") private val successRate: Double,
    private val random: Random = Random.Default
) {
    private val logger = LoggerFactory.getLogger(PaymentService::class.java)

    @Transactional
    fun processPayment(request: PaymentRequest): Payment {
        if (request.method !in PaymentMethod.ALL) {
            throw ServerException(message = "지원하지 않는 결제 수단입니다", code = ErrorCode.INVALID_METHOD)
        }

        val payment = paymentRepository.save(
            Payment(
                seatId = request.seatId,
                userId = request.userId,
                eventId = request.eventId,
                amount = request.amount,
                method = request.method,
                status = PaymentStatus.PENDING
            )
        )

        val success = random.nextDouble() < successRate
        payment.status = if (success) PaymentStatus.SUCCEEDED else PaymentStatus.FAILED
        payment.completedAt = LocalDateTime.now()

        logger.info("Payment processed: id={}, seatId={}, userId={}, status={}",
            payment.id, payment.seatId, payment.userId, payment.status)

        return payment
    }

    fun getById(id: Long): Payment =
        paymentRepository.findById(id).orElseThrow {
            ServerException(message = "결제 정보를 찾을 수 없습니다", code = ErrorCode.PAYMENT_NOT_FOUND)
        }

    fun getByUserId(userId: Long): List<Payment> =
        paymentRepository.findByUserIdOrderByCreatedAtDesc(userId)
}

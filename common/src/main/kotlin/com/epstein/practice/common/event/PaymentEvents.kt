package com.epstein.practice.common.event

/**
 * `payment.events` 토픽 메시지.
 * Saga Orchestrator ↔ payment-service 간 결제 응답 이벤트.
 */

/** 결제 생성 완료 시 payment-service가 발행. Orchestrator가 step 전이 */
data class PaymentCreated(
    val sagaId: Long,
    val seatId: Long,
    val userId: Long,
    val paymentId: Long,
)

/** 결제 성공 시 payment-service가 발행. Orchestrator가 seat RESERVED로 전이 */
data class PaymentSucceeded(
    val sagaId: Long,
    val seatId: Long,
    val userId: Long,
    val paymentId: Long,
)

/** 결제 실패 시 payment-service가 발행. Orchestrator가 보상 실행 */
data class PaymentFailed(
    val sagaId: Long,
    val seatId: Long,
    val userId: Long,
    val paymentId: Long?,
    val reason: String,
)

/** HOLD 만료로 Payment가 EXPIRED 전이 시 payment-service가 발행 */
data class PaymentExpired(
    val sagaId: Long,
    val seatId: Long,
    val paymentId: Long,
)

/** 사용자 취소로 Payment가 CANCELLED 전이 시 payment-service가 발행 */
data class PaymentCancelled(
    val sagaId: Long,
    val seatId: Long,
    val paymentId: Long,
)

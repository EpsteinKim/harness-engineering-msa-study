package com.epstein.practice.common.event

/**
 * `payment.events` 토픽 메시지.
 * reserve-service ↔ payment-service 간 결제 Saga 이벤트.
 */

/** /pay 호출 시 reserve-service가 발행. payment-service가 소비해 실제 결제 시도 */
data class PaymentRequested(
    val seatId: Long,
    val userId: Long,
    val method: String
)

/** 결제 성공 시 payment-service가 발행. reserve-service가 seat RESERVED로 전이 */
data class PaymentSucceeded(
    val seatId: Long,
    val userId: Long,
    val paymentId: Long
)

/** 결제 실패 시 payment-service가 발행. reserve-service가 seat AVAILABLE로 복구 */
data class PaymentFailed(
    val seatId: Long,
    val userId: Long,
    val paymentId: Long?,
    val reason: String
)

/** HOLD 만료로 Payment가 EXPIRED 전이 시 payment-service가 발행 (관측용) */
data class PaymentExpired(
    val seatId: Long,
    val paymentId: Long
)

/** 사용자 취소로 Payment가 CANCELLED 전이 시 payment-service가 발행 (관측용) */
data class PaymentCancelled(
    val seatId: Long,
    val paymentId: Long
)

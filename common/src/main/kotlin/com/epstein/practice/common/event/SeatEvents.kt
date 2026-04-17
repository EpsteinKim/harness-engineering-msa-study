package com.epstein.practice.common.event

/**
 * `seat.events` 토픽 메시지.
 * reserve-service가 발행, payment-service + reserve-service(self)가 소비.
 * common 모듈에 둬서 모든 서비스가 동일 FQN으로 JSON 역직렬화 가능.
 */
data class SeatHeld(
    val seatId: Long,
    val userId: Long,
    val eventId: Long,
    val section: String,
    val amount: Long,
    val heldUntilMs: Long
)

/** 결제 완료로 좌석 확정 시 발행 (Phase C). 관측·감사 용도 */
data class SeatReserved(
    val seatId: Long,
    val userId: Long,
    val eventId: Long,
    val paymentId: Long
)

/** 좌석이 다시 풀려나는 모든 경우: 사용자 취소 / 결제 실패 */
data class SeatReleased(
    val seatId: Long,
    val userId: Long,
    val eventId: Long,
    val reason: SeatReleaseReason
)

enum class SeatReleaseReason {
    CANCELLED,        // 사용자 취소
    PAYMENT_FAILED,   // 결제 실패
}

/** HOLD TTL 초과로 좌석이 자동 반환될 때 발행 (Phase D). reserve/payment 양쪽이 consume */
data class HoldExpired(
    val seatId: Long,
    val userId: Long,
    val eventId: Long
)

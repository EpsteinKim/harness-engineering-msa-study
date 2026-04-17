package com.epstein.practice.common.event

/**
 * `system.ticks` 토픽 메시지. core-service가 발행, 다른 서비스가 소비.
 * 파티션 1개로 중복 실행 방지.
 */

/** 이벤트 OPEN/CLOSE/SYNC 트리거 */
data class EventLifecycleTick(
    val phase: LifecyclePhase,
    val tickedAt: Long = System.currentTimeMillis()
)

enum class LifecyclePhase {
    OPEN, CLOSE, SYNC
}

/** HOLD 만료 검사 트리거. reserve-service가 DB 조회 후 HoldExpired 발행 */
data class HoldExpiryTick(
    val tickedAt: Long = System.currentTimeMillis()
)

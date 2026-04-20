package com.epstein.practice.reserveservice.type.event

/**
 * `reserve.queue` 토픽 메시지.
 *
 * - key: "${eventId}:${userId % K}" (같은 이벤트 + 같은 버킷은 동일 파티션 = 이벤트 내 FIFO 유지)
 * - 실제 좌석 배정은 `QueueConsumer`에서 수행
 */
data class EnqueueMessage(
    val eventId: Long,
    val userId: String,
    val seatId: Long? = null,
    val section: String? = null,
    val joinedAt: Long
)

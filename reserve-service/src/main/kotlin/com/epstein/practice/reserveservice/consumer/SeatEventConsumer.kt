package com.epstein.practice.reserveservice.consumer

import com.epstein.practice.common.event.HoldExpired
import com.epstein.practice.common.event.SeatHeld
import com.epstein.practice.common.event.SeatReleased
import com.epstein.practice.common.event.SeatReserved
import com.epstein.practice.reserveservice.config.KafkaConfig
import org.springframework.kafka.annotation.KafkaHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * `seat.events` 토픽 consumer (reserve-service 측 self-consume).
 *
 * 좌석 상태 전이는 SagaOrchestrator가 담당.
 * 여기서는 자기 발행 이벤트를 소비하되 추가 액션 없음 (KafkaHandler 기본 핸들러).
 */
@Component
@KafkaListener(topics = [KafkaConfig.TOPIC_SEAT_EVENTS])
class SeatEventConsumer {

    @KafkaHandler
    fun onHoldExpired(event: HoldExpired) { /* Orchestrator가 타임아웃 보상 처리 */ }

    @KafkaHandler
    fun onHeld(event: SeatHeld) { /* self-published */ }

    @KafkaHandler
    fun onReserved(event: SeatReserved) { /* no-op */ }

    @KafkaHandler
    fun onReleased(event: SeatReleased) { /* self-published */ }
}

package com.epstein.practice.paymentservice.consumer

import com.epstein.practice.common.event.HoldExpired
import com.epstein.practice.common.event.SeatHeld
import com.epstein.practice.common.event.SeatReleased
import com.epstein.practice.common.event.SeatReserved
import com.epstein.practice.paymentservice.config.KafkaConfig
import org.springframework.kafka.annotation.KafkaHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * `seat.events` 토픽 consumer.
 *
 * Saga 오케스트레이션 전환으로 SeatHeld → Payment 생성은 커맨드(CreatePaymentCommand)가 담당.
 * 이 consumer는 이벤트 수신만 하고 추가 액션 없음.
 */
@Component
@KafkaListener(topics = [KafkaConfig.TOPIC_SEAT_EVENTS])
class SeatEventConsumer {

    @KafkaHandler
    fun onSeatHeld(event: SeatHeld) { /* 커맨드로 대체됨 */ }

    @KafkaHandler
    fun onHoldExpired(event: HoldExpired) { /* Orchestrator가 타임아웃 처리 */ }

    @KafkaHandler
    fun onSeatReleased(event: SeatReleased) { /* Orchestrator가 취소 처리 */ }

    @KafkaHandler
    fun onSeatReserved(event: SeatReserved) { /* no-op */ }
}

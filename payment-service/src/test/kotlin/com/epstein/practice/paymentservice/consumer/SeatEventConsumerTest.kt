package com.epstein.practice.paymentservice.consumer

import com.epstein.practice.common.event.SeatHeld
import com.epstein.practice.common.event.SeatReleased
import com.epstein.practice.common.event.SeatReleaseReason
import com.epstein.practice.common.event.HoldExpired
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SeatEventConsumerTest {

    private val consumer = SeatEventConsumer()

    @Test
    @DisplayName("SeatHeld 수신 시 no-op (커맨드로 대체됨)")
    fun onSeatHeldNoOp() {
        consumer.onSeatHeld(SeatHeld(seatId = 100L, userId = 5L, eventId = 1L, section = "A", amount = 200_000L, heldUntilMs = 0L))
    }

    @Test
    @DisplayName("HoldExpired 수신 시 no-op (Orchestrator가 처리)")
    fun onHoldExpiredNoOp() {
        consumer.onHoldExpired(HoldExpired(seatId = 100L, userId = 5L, eventId = 1L))
    }

    @Test
    @DisplayName("SeatReleased 수신 시 no-op (Orchestrator가 처리)")
    fun onSeatReleasedNoOp() {
        consumer.onSeatReleased(SeatReleased(seatId = 100L, userId = 5L, eventId = 1L, reason = SeatReleaseReason.CANCELLED))
    }
}

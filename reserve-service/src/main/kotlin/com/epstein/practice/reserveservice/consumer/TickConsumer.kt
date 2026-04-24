package com.epstein.practice.reserveservice.consumer

import com.epstein.practice.common.event.EventLifecycleTick
import com.epstein.practice.common.event.HoldExpiryTick
import com.epstein.practice.common.event.LifecyclePhase
import com.epstein.practice.reserveservice.config.KafkaConfig
import com.epstein.practice.reserveservice.main.service.SeatSyncService
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
@KafkaListener(topics = [KafkaConfig.TOPIC_SYSTEM_TICKS])
class TickConsumer(
    private val seatSyncService: SeatSyncService,
) {
    private val logger = LoggerFactory.getLogger(TickConsumer::class.java)

    @KafkaHandler
    fun onLifecycleTick(tick: EventLifecycleTick) {
        when (tick.phase) {
            LifecyclePhase.SYNC -> {
                val synced = seatSyncService.syncAllRemainingSeats()
                if (synced > 0) logger.info("Synced {} events via tick", synced)
            }
            else -> { /* OPEN/CLOSE are handled directly by core-service */ }
        }
    }

    @KafkaHandler
    fun onHoldExpiryTick(tick: HoldExpiryTick) {
        // SagaTimeoutScheduler가 Saga 테이블 기반으로 타임아웃 처리
    }
}

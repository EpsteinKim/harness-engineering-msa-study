package com.epstein.practice.reserveservice.consumer

import com.epstein.practice.common.event.EventLifecycleTick
import com.epstein.practice.common.event.HoldExpired
import com.epstein.practice.common.event.HoldExpiryTick
import com.epstein.practice.common.event.LifecyclePhase
import com.epstein.practice.reserveservice.config.KafkaConfig
import com.epstein.practice.reserveservice.config.ReserveConfig
import com.epstein.practice.reserveservice.type.entity.SeatStatus
import com.epstein.practice.reserveservice.main.repository.SeatRepository
import com.epstein.practice.reserveservice.main.service.SeatSyncService
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
@KafkaListener(topics = [KafkaConfig.TOPIC_SYSTEM_TICKS])
class TickConsumer(
    private val seatSyncService: SeatSyncService,
    private val seatRepository: SeatRepository,
    private val kafkaTemplate: KafkaTemplate<String, Any>,
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
        val threshold = LocalDateTime.now().minusNanos(ReserveConfig.HOLD_TTL_MS * 1_000_000)
        val expired = seatRepository.findExpiredHolds(SeatStatus.PAYMENT_PENDING, threshold)
        if (expired.isEmpty()) return

        logger.info("Publishing HoldExpired for {} seats via tick", expired.size)
        for (seat in expired) {
            val userId = seat.userId ?: continue
            kafkaTemplate.send(
                KafkaConfig.TOPIC_SEAT_EVENTS,
                seat.id.toString(),
                HoldExpired(seatId = seat.id, userId = userId, eventId = seat.eventId)
            )
        }
    }
}

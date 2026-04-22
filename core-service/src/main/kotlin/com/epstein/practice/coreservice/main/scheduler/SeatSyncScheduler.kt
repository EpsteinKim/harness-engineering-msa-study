package com.epstein.practice.coreservice.main.scheduler

import com.epstein.practice.common.event.EventLifecycleTick
import com.epstein.practice.common.event.LifecyclePhase
import com.epstein.practice.coreservice.config.KafkaConfig
import org.slf4j.LoggerFactory
import com.epstein.practice.common.outbox.OutboxService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class SeatSyncScheduler(
    private val outboxService: OutboxService,
) {
    private val logger = LoggerFactory.getLogger(SeatSyncScheduler::class.java)

    @Scheduled(cron = "0 */5 * * * *")
    fun run() {
        logger.debug("Publishing SeatSync tick")
        outboxService.save(KafkaConfig.TOPIC_SYSTEM_TICKS, null, EventLifecycleTick(LifecyclePhase.SYNC))
    }
}

package com.epstein.practice.coreservice.main.scheduler

import com.epstein.practice.common.event.HoldExpiryTick
import com.epstein.practice.coreservice.config.KafkaConfig
import org.slf4j.LoggerFactory
import com.epstein.practice.common.outbox.OutboxService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class HoldExpiryScheduler(
    private val outboxService: OutboxService,
) {
    private val logger = LoggerFactory.getLogger(HoldExpiryScheduler::class.java)

    @Scheduled(fixedDelay = 10_000)
    fun run() {
        logger.debug("Publishing HoldExpiryTick")
        outboxService.save(KafkaConfig.TOPIC_SYSTEM_TICKS, null, HoldExpiryTick())
    }
}

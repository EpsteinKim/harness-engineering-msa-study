package com.epstein.practice.coreservice.scheduler

import com.epstein.practice.common.event.EventLifecycleTick
import com.epstein.practice.common.event.LifecyclePhase
import com.epstein.practice.coreservice.config.KafkaConfig
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class SeatSyncScheduler(
    private val kafkaTemplate: KafkaTemplate<String, Any>,
) {
    private val logger = LoggerFactory.getLogger(SeatSyncScheduler::class.java)

    @Scheduled(cron = "0 */5 * * * *")
    fun run() {
        logger.debug("Publishing SeatSync tick")
        kafkaTemplate.send(KafkaConfig.TOPIC_SYSTEM_TICKS, EventLifecycleTick(LifecyclePhase.SYNC))
    }
}

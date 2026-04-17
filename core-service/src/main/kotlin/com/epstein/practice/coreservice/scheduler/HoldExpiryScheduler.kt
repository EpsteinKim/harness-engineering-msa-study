package com.epstein.practice.coreservice.scheduler

import com.epstein.practice.common.event.HoldExpiryTick
import com.epstein.practice.coreservice.config.KafkaConfig
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class HoldExpiryScheduler(
    private val kafkaTemplate: KafkaTemplate<String, Any>,
) {
    private val logger = LoggerFactory.getLogger(HoldExpiryScheduler::class.java)

    @Scheduled(fixedDelay = 10_000)
    fun run() {
        logger.debug("Publishing HoldExpiryTick")
        kafkaTemplate.send(KafkaConfig.TOPIC_SYSTEM_TICKS, HoldExpiryTick())
    }
}

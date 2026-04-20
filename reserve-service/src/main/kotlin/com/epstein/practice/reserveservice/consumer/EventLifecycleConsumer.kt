package com.epstein.practice.reserveservice.consumer

import com.epstein.practice.common.event.EventClosedRequest
import com.epstein.practice.common.event.EventOpenedRequest
import com.epstein.practice.reserveservice.config.KafkaConfig
import com.epstein.practice.reserveservice.main.service.SeatSyncService
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
@KafkaListener(topics = [KafkaConfig.TOPIC_EVENT_LIFECYCLE])
class EventLifecycleConsumer(
    private val seatSyncService: SeatSyncService,
) {
    private val logger = LoggerFactory.getLogger(EventLifecycleConsumer::class.java)

    @KafkaHandler
    fun onEventOpened(event: EventOpenedRequest) {
        logger.info("EventOpened received: eventId={}, name={}", event.eventId, event.name)
        seatSyncService.warmSeatData(event.eventId, event.seatSelectionType)
    }

    @KafkaHandler
    fun onEventClosed(event: EventClosedRequest) {
        logger.info("EventClosed received: eventId={}", event.eventId)
        seatSyncService.clearSeatData(event.eventId)
    }
}

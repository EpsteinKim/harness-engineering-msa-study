package com.epstein.practice.coreservice.main.service

import com.epstein.practice.common.event.EventClosedRequest
import com.epstein.practice.common.event.EventOpenedRequest
import com.epstein.practice.coreservice.config.KafkaConfig
import com.epstein.practice.coreservice.main.cache.EventCacheRepository
import com.epstein.practice.coreservice.type.entity.Event
import com.epstein.practice.coreservice.type.entity.EventStatus
import com.epstein.practice.coreservice.main.repository.EventRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset

@Service
class EventLifecycleService(
    private val eventRepository: EventRepository,
    private val eventCache: EventCacheRepository,
    private val kafkaTemplate: KafkaTemplate<String, Any>,
) {
    private val logger = LoggerFactory.getLogger(EventLifecycleService::class.java)

    @Transactional
    fun openEvents(): Int {
        val now = LocalDateTime.now()
        val events = eventRepository.findEventsToOpen(EventStatus.CLOSED, now)

        for (event in events) {
            event.status = EventStatus.OPEN
            eventRepository.save(event)
            cacheEventMetadata(event)
            publishEventOpened(event)
            logger.info("Opened event: id={}, name={}", event.id, event.name)
        }
        return events.size
    }

    @Transactional
    fun closeEvents(): Int {
        val now = LocalDateTime.now()
        val events = eventRepository.findEventsToClose(EventStatus.OPEN, now)

        for (event in events) {
            event.status = EventStatus.CLOSED
            eventRepository.save(event)
            eventCache.removeOpenEventIndex(event.id)
            eventCache.deleteEvent(event.id)
            kafkaTemplate.send(KafkaConfig.TOPIC_EVENT_LIFECYCLE, EventClosedRequest(eventId = event.id))
            logger.info("Closed event: id={}, name={}", event.id, event.name)
        }
        return events.size
    }

    fun warmupCache(): Int {
        val openEvents = eventRepository.findByStatus(EventStatus.OPEN)
        for (event in openEvents) {
            cacheEventMetadata(event)
            publishEventOpened(event)
            logger.info("Warmed up cache for event: id={}, name={}", event.id, event.name)
        }
        return openEvents.size
    }

    private fun cacheEventMetadata(event: Event) {
        val fields = mapOf(
            "id" to event.id.toString(),
            "name" to event.name,
            "status" to event.status.name,
            "ticketOpenTime" to (event.ticketOpenTime?.toString() ?: ""),
            "ticketCloseTime" to (event.ticketCloseTime?.toString() ?: ""),
            "eventTime" to event.eventTime.toString(),
            "seatSelectionType" to event.seatSelectionType.name,
        )
        eventCache.saveEvent(event.id, fields)

        val closeTime = event.ticketCloseTime
        if (closeTime != null) {
            val ttl = Duration.between(LocalDateTime.now(), closeTime)
            if (!ttl.isNegative) {
                eventCache.expireEvent(event.id, ttl)
            }
        }

        if (event.status == EventStatus.OPEN) {
            val score = event.ticketOpenTime?.toEpochSecond(ZoneOffset.UTC)?.toDouble() ?: 0.0
            eventCache.addOpenEventIndex(event.id, score)
        } else {
            eventCache.removeOpenEventIndex(event.id)
        }
    }

    private fun publishEventOpened(event: Event) {
        kafkaTemplate.send(
            KafkaConfig.TOPIC_EVENT_LIFECYCLE,
            EventOpenedRequest(
                eventId = event.id,
                name = event.name,
                eventTime = event.eventTime.toString(),
                ticketCloseTime = event.ticketCloseTime?.toString(),
                seatSelectionType = event.seatSelectionType.name,
            )
        )
    }
}

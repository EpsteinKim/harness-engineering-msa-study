package com.epstein.practice.reserveservice.scheduler

import com.epstein.practice.reserveservice.service.EventService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class EventScheduler(
    private val eventService: EventService
) {
    private val logger = LoggerFactory.getLogger(EventScheduler::class.java)

    @Scheduled(cron = "0 0 * * * *")
    fun openEvent() {
        val opened = eventService.openEvents()
        val closed = eventService.closeEvents()
        if (opened > 0) logger.info("Opened {} events", opened)
        if (closed > 0) logger.info("Closed {} events", closed)
    }

    @Scheduled(cron = "0 */5 * * * *")
    fun syncRemainingSeats() {
        val synced = eventService.syncAllRemainingSeats()
        if (synced > 0) logger.info("Synced remaining seats for {} events", synced)
    }
}

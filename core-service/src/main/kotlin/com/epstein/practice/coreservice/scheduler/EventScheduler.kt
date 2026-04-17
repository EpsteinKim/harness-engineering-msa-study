package com.epstein.practice.coreservice.scheduler

import com.epstein.practice.coreservice.service.EventLifecycleService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class EventScheduler(
    private val eventLifecycleService: EventLifecycleService,
) {
    private val logger = LoggerFactory.getLogger(EventScheduler::class.java)

    @Scheduled(cron = "0 0 * * * *")
    fun run() {
        val opened = eventLifecycleService.openEvents()
        val closed = eventLifecycleService.closeEvents()
        if (opened > 0 || closed > 0) {
            logger.info("Event lifecycle: opened={}, closed={}", opened, closed)
        }
    }
}

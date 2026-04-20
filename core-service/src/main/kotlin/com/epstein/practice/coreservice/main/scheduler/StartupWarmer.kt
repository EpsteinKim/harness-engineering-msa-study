package com.epstein.practice.coreservice.main.scheduler

import com.epstein.practice.coreservice.main.service.EventLifecycleService
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class StartupWarmer(
    private val eventLifecycleService: EventLifecycleService
) {
    private val logger = LoggerFactory.getLogger(StartupWarmer::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun onStartup() {
        logger.info("Warming up event metadata cache")
        val warmed = eventLifecycleService.warmupCache()
        if (warmed > 0) logger.info("Warmed up cache for {} open events", warmed)
    }
}

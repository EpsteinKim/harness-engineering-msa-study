package com.epstein.practice.reserveservice.scheduler

import com.epstein.practice.reserveservice.main.cache.EventCacheRepository
import com.epstein.practice.reserveservice.main.cache.QueueCacheRepository
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

@Component
class QueueRecoveryScheduler(
    private val queueCache: QueueCacheRepository,
    private val eventCache: EventCacheRepository,
    private val redis: StringRedisTemplate,
) {
    private val logger = LoggerFactory.getLogger(QueueRecoveryScheduler::class.java)
    private val podId = UUID.randomUUID().toString()

    private val unlockScript = DefaultRedisScript<Long>(
        """
        if redis.call('get', KEYS[1]) == ARGV[1] then
            return redis.call('del', KEYS[1])
        else
            return 0
        end
        """.trimIndent(),
        Long::class.java
    )

    @Scheduled(fixedDelay = 10_000)
    fun recover() {
        val acquired = redis.opsForValue()
            .setIfAbsent("lock:queue-recovery", podId, Duration.ofSeconds(15))
        if (acquired != true) return

        try {
            val openEventIds = eventCache.getOpenEventIdsOrderedByTicketOpenTime()

            for (eventId in openEventIds) {
                val entries = queueCache.getProcessingEntries(eventId)
                if (entries.isEmpty()) continue

                var recovered = 0
                for (entry in entries) {
                    queueCache.recoverToQueue(eventId, entry)
                    recovered++
                }
                logger.info("Queue recovery: eventId={}, recovered={}", eventId, recovered)
            }
        } finally {
            redis.execute(unlockScript, listOf("lock:queue-recovery"), podId)
        }
    }
}

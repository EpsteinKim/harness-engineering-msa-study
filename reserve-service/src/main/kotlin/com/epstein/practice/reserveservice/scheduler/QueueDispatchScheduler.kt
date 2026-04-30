package com.epstein.practice.reserveservice.scheduler

import com.epstein.practice.reserveservice.config.KafkaConfig
import com.epstein.practice.reserveservice.config.KafkaConfig.Companion.PARTITION_BUCKETS
import com.epstein.practice.reserveservice.main.cache.EventCacheRepository
import com.epstein.practice.reserveservice.main.cache.QueueCacheRepository
import com.epstein.practice.reserveservice.type.event.EnqueueMessage
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID
import kotlin.math.absoluteValue

@Component
class QueueDispatchScheduler(
    private val queueCache: QueueCacheRepository,
    private val eventCache: EventCacheRepository,
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    private val redis: StringRedisTemplate,
) {
    private val logger = LoggerFactory.getLogger(QueueDispatchScheduler::class.java)
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

    @PostConstruct
    fun init() {
        logger.info("QueueDispatchScheduler ready: podId={}, podHash={}", podId, podId.hashCode())
    }

    @Scheduled(fixedDelay = 200)
    fun dispatch() {
        val openEventIds = eventCache.getOpenEventIdsOrderedByTicketOpenTime()
        if (openEventIds.isEmpty()) return

        // 파드별 시작 오프셋: 같은 순서 리스트를 podId 해시로 회전시켜 head-of-line 분산
        val offset = (podId.hashCode() % openEventIds.size).absoluteValue
        val ordered = openEventIds.drop(offset) + openEventIds.take(offset)

        for (eventId in ordered) {
            val lockKey = "lock:queue-dispatch:$eventId"
            val acquired = redis.opsForValue()
                .setIfAbsent(lockKey, podId, Duration.ofSeconds(2))
            if (acquired != true) continue

            try {
                val entries = queueCache.popForDispatch(eventId, 1000)
                if (entries.isEmpty()) continue

                val futures = entries.mapNotNull { entry ->
                    val userIdLong = entry.userId.toLongOrNull() ?: return@mapNotNull null
                    val bucket = (userIdLong % PARTITION_BUCKETS).absoluteValue
                    val key = "$eventId:$bucket"
                    val message = EnqueueMessage(
                        eventId = eventId,
                        userId = entry.userId,
                        seatId = entry.seatId,
                        section = entry.section,
                        joinedAt = entry.score,
                    )
                    Triple(entry, key, kafkaTemplate.send(KafkaConfig.TOPIC_RESERVE_QUEUE, key, message))
                }

                var sent = 0
                for ((entry, _, future) in futures) {
                    try {
                        future.get()
                        queueCache.removeFromProcessing(eventId, entry.userId)
                        sent++
                    } catch (e: Exception) {
                        logger.error("Kafka send failed: eventId={}, userId={}: {}", eventId, entry.userId, e.message)
                    }
                }

                logger.info("Queue dispatch: eventId={}, sent={}/{}", eventId, sent, entries.size)
            } finally {
                redis.execute(unlockScript, listOf(lockKey), podId)
            }
        }
    }
}

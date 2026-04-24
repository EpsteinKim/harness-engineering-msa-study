package com.epstein.practice.reserveservice.main.service

import com.epstein.practice.reserveservice.config.ReserveConfig
import com.epstein.practice.reserveservice.main.repository.SagaRepository
import com.epstein.practice.reserveservice.type.constant.SagaStatus
import com.epstein.practice.reserveservice.type.constant.SagaStep
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.ZonedDateTime
import java.util.UUID

@Component
class SagaTimeoutScheduler(
    private val sagaRepository: SagaRepository,
    private val sagaOrchestrator: SagaOrchestrator,
    private val redis: StringRedisTemplate,
) {
    private val logger = LoggerFactory.getLogger(SagaTimeoutScheduler::class.java)
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
    fun checkTimeouts() {
        val acquired = redis.opsForValue()
            .setIfAbsent("lock:saga-timeout", podId, Duration.ofSeconds(30))
        if (acquired != true) return

        try {
            val threshold = ZonedDateTime.now().minusNanos(ReserveConfig.HOLD_TTL_MS * 1_000_000)
            val expired = sagaRepository.findExpiredSagas(
                status = SagaStatus.IN_PROGRESS,
                steps = listOf(SagaStep.SEAT_HELD, SagaStep.PAYMENT_CREATED),
                threshold = threshold,
            )
            if (expired.isEmpty()) return

            logger.info("Found {} expired sagas", expired.size)
            for (saga in expired) {
                sagaOrchestrator.onTimeout(saga.id)
            }
        } finally {
            redis.execute(unlockScript, listOf("lock:saga-timeout"), podId)
        }
    }
}

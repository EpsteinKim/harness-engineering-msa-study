package com.epstein.practice.queueservice.scheduler

import com.epstein.practice.queueservice.service.QueueService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class QueueScheduler(
    private val queueService: QueueService,
    @Value("\${queue.throttle.rate}") private val throttleRate: Long
) {
    private val logger = LoggerFactory.getLogger(QueueScheduler::class.java)
    private val restClient = RestClient.create()

    @Scheduled(fixedRate = 1000)
    fun processQueues() {
        val queueNames = queueService.getActiveQueues()
        for (queueName in queueNames) {
            processQueue(queueName)
        }
    }

    private fun processQueue(queueName: String) {
        val dequeued = queueService.dequeue(queueName, throttleRate)
        if (dequeued.isEmpty()) return

        logger.info("[{}] Dequeued {} requests", queueName, dequeued.size)

        for (userId in dequeued) {
            val metadata = queueService.getRequestMetadata(queueName, userId)
            if (metadata == null) {
                logger.warn("[{}] No metadata for user {}", queueName, userId)
                queueService.fail(queueName, userId)
                continue
            }
            try {
                val response = restClient.post()
                    .uri(metadata.callbackUrl)
                    .header("Content-Type", "application/json")
                    .body(metadata.payloadJson)
                    .retrieve()
                    .body(Map::class.java)

                val status = response?.get("status") as? String
                if (status == "success") {
                    queueService.complete(queueName, userId)
                    logger.info("[{}] Callback succeeded: user={}", queueName, userId)
                } else {
                    queueService.fail(queueName, userId)
                    val message = response?.get("message") ?: "Unknown error"
                    logger.info("[{}] Callback failed: user={}, reason={}", queueName, userId, message)
                }
            } catch (e: Exception) {
                logger.error("[{}] Callback error for user {}: {}", queueName, userId, e.message)
                queueService.fail(queueName, userId)
            }
        }
    }

    @Scheduled(fixedRate = 30000)
    fun reEnqueueExpired() {
        val count = queueService.reEnqueueExpired()
        if (count > 0) {
            logger.info("Re-enqueued {} expired requests across all queues", count)
        }
    }
}

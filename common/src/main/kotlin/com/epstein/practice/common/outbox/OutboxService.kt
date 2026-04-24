package com.epstein.practice.common.outbox

import tools.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service

@Service
class OutboxService(
    private val outboxRepository: OutboxRepository,
    private val objectMapper: ObjectMapper,
) {
    fun save(topic: String, key: String?, payload: Any) {
        outboxRepository.save(
            OutboxEvent(
                topic = topic,
                key = key,
                payload = objectMapper.writeValueAsString(payload),
                eventType = payload::class.java.name,
            )
        )
    }
}

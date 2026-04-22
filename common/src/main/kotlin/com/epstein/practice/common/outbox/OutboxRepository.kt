package com.epstein.practice.common.outbox

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface OutboxRepository : JpaRepository<OutboxEvent, Long> {

    @Query(
        value = "SELECT * FROM outbox ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED",
        nativeQuery = true
    )
    fun findPendingForUpdate(limit: Int): List<OutboxEvent>
}

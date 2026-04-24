package com.epstein.practice.reserveservice.main.repository

import com.epstein.practice.reserveservice.type.constant.SagaStatus
import com.epstein.practice.reserveservice.type.constant.SagaStep
import com.epstein.practice.reserveservice.type.entity.ReservationSaga
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.ZonedDateTime

interface SagaRepository : JpaRepository<ReservationSaga, Long> {

    fun findByEventIdAndUserIdAndStatusIn(eventId: Long, userId: Long, statuses: List<SagaStatus>): ReservationSaga?

    @Query(
        """
        SELECT s FROM ReservationSaga s
        WHERE s.status = :status
          AND s.step IN :steps
          AND s.updatedAt < :threshold
        """
    )
    fun findExpiredSagas(status: SagaStatus, steps: List<SagaStep>, threshold: ZonedDateTime): List<ReservationSaga>
}

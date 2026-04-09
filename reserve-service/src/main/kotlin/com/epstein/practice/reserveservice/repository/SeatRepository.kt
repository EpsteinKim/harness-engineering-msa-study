package com.epstein.practice.reserveservice.repository

import com.epstein.practice.reserveservice.entity.Seat
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param


interface SeatRepository : JpaRepository<Seat, Long> {
    fun findByEventIdAndId(eventId: Long, id: Long): Seat?
    fun findByEventId(eventId: Long): List<Seat>
    fun findByEventIdAndSection(eventId: Long, section: String): List<Seat>

    @Query(
        value = """
            SELECT * FROM seat
            WHERE event_id = :eventId
              AND section = :section
              AND status = 'AVAILABLE'
            ORDER BY id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true
    )
    fun findFirstAvailableSeatForUpdate(
        @Param("eventId") eventId: Long,
        @Param("section") section: String
    ): Seat?

    @Query("SELECT COUNT(s) FROM Seat s WHERE s.event.id = :eventId AND s.status = com.epstein.practice.reserveservice.entity.SeatStatus.AVAILABLE")
    fun countAvailableSeats(@Param("eventId") eventId: Long): Long
}

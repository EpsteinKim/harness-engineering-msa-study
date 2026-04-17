package com.epstein.practice.reserveservice.repository

import com.epstein.practice.reserveservice.entity.Seat
import com.epstein.practice.reserveservice.entity.SeatStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime


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

    @Query("SELECT COUNT(s) FROM Seat s WHERE s.eventId = :eventId AND s.status = com.epstein.practice.reserveservice.entity.SeatStatus.AVAILABLE")
    fun countAvailableSeats(@Param("eventId") eventId: Long): Long

    fun findByEventIdAndUserId(eventId: Long, userId: Long): Seat?
    fun findByEventIdAndUserIdAndStatus(eventId: Long, userId: Long, status: com.epstein.practice.reserveservice.entity.SeatStatus): Seat?

    @Query("""
        SELECT s FROM Seat s
        WHERE s.userId = :userId
          AND s.status IN (com.epstein.practice.reserveservice.entity.SeatStatus.PAYMENT_PENDING,
                           com.epstein.practice.reserveservice.entity.SeatStatus.RESERVED)
    """)
    fun findActiveByUserId(@Param("userId") userId: Long): List<Seat>

    @Query("SELECT s.section AS section, MIN(s.priceAmount) AS price FROM Seat s WHERE s.eventId = :eventId GROUP BY s.section")
    fun findSectionPrices(@Param("eventId") eventId: Long): List<SectionPriceProjection>

    @Query("""
        SELECT s FROM Seat s
        WHERE s.status = :status
          AND s.reservedAt IS NOT NULL
          AND s.reservedAt < :threshold
    """)
    fun findExpiredHolds(
        @Param("status") status: SeatStatus,
        @Param("threshold") threshold: LocalDateTime
    ): List<Seat>
}

interface SectionPriceProjection {
    val section: String
    val price: Long
}

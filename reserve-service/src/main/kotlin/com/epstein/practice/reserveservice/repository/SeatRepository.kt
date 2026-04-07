package com.epstein.practice.reserveservice.repository

import com.epstein.practice.reserveservice.entity.Seat
import org.springframework.data.jpa.repository.JpaRepository

interface SeatRepository : JpaRepository<Seat, Long> {
    fun findByEventIdAndId(eventId: Long, id: Long): Seat?
}

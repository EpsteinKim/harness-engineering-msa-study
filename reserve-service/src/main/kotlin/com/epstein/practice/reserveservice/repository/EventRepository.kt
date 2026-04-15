package com.epstein.practice.reserveservice.repository

import com.epstein.practice.reserveservice.entity.Event
import com.epstein.practice.reserveservice.entity.EventStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface EventRepository : JpaRepository<Event, Long> {

    @Query("SELECT e FROM Event e WHERE e.status = :status AND e.ticketOpenTime <= :now AND e.ticketCloseTime > :now")
    fun findEventsToOpen(@Param("status") status: EventStatus, @Param("now") now: LocalDateTime): List<Event>

    @Query("SELECT e FROM Event e WHERE e.status = :status AND e.ticketCloseTime <= :now")
    fun findEventsToClose(@Param("status") status: EventStatus, @Param("now") now: LocalDateTime): List<Event>

    fun findByStatus(status: EventStatus): List<Event>
    fun findByStatusOrderByTicketOpenTimeAsc(status: EventStatus): List<Event>
}

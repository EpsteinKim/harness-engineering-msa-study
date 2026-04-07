package com.epstein.practice.reserveservice.repository

import com.epstein.practice.reserveservice.entity.Event
import org.springframework.data.jpa.repository.JpaRepository

interface EventRepository : JpaRepository<Event, Long>

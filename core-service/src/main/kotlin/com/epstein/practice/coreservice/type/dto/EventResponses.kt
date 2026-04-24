package com.epstein.practice.coreservice.type.dto

import java.time.ZonedDateTime
import java.time.ZoneId

data class EventSummaryResponse(
    val id: Long,
    val name: String,
    val eventTime: ZonedDateTime,
    val status: String,
    val ticketOpenTime: ZonedDateTime?,
    val ticketCloseTime: ZonedDateTime?,
    val seatSelectionType: String,
    val remainingSeats: Long,
)

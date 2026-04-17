package com.epstein.practice.coreservice.dto

import java.time.LocalDateTime

data class EventSummaryResponse(
    val id: Long,
    val name: String,
    val eventTime: LocalDateTime,
    val status: String,
    val ticketOpenTime: LocalDateTime?,
    val ticketCloseTime: LocalDateTime?,
    val seatSelectionType: String,
    val remainingSeats: Long,
)

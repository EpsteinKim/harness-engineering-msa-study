package com.epstein.practice.common.event

data class EventOpenedRequest(
    val eventId: Long,
    val name: String,
    val eventTime: String,
    val ticketCloseTime: String?,
    val seatSelectionType: String,
)

data class EventClosedRequest(
    val eventId: Long,
)

package com.epstein.practice.common.cache

fun eventCacheKey(eventId: Long) = "event:$eventId"
fun seatCacheKey(eventId: Long) = "event:$eventId:seats"
fun sectionAvailableField(section: String) = "section:$section:available"
fun sectionTotalField(section: String) = "section:$section:total"
fun sectionPriceField(section: String) = "section:$section:price"
fun seatPriceField(seatId: Long) = "seat_price:$seatId"
const val SEAT_PRICE_FIELD_PREFIX = "seat_price:"
const val OPEN_EVENTS_INDEX_KEY = "events:open"

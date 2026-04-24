package com.epstein.practice.reserveservice.type.constant

enum class SagaStep {
    SEAT_HELD,
    PAYMENT_CREATED,
    PAYMENT_PROCESSING,
    COMPLETED,
    COMPENSATING,
    COMPENSATED,
}

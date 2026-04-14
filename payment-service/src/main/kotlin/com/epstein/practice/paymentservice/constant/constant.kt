package com.epstein.practice.paymentservice.constant

object ErrorCode {
    const val PAYMENT_NOT_FOUND = "PAYMENT_NOT_FOUND"
    const val INVALID_METHOD = "INVALID_METHOD"
}

object PaymentMethod {
    const val CARD = "CARD"
    const val ACCOUNT = "ACCOUNT"
    val ALL = setOf(CARD, ACCOUNT)
}

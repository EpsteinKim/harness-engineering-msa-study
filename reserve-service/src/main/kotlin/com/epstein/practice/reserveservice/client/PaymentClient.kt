package com.epstein.practice.reserveservice.client

import org.slf4j.LoggerFactory
import org.springframework.core.ParameterizedTypeReference
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class PaymentClient(
    private val paymentServiceClient: RestClient
) {
    private val logger = LoggerFactory.getLogger(PaymentClient::class.java)

    fun listByUser(userId: Long): List<PaymentSummary> {
        return try {
            val response = paymentServiceClient.get()
                .uri("/api/v1/payments?userId={userId}", userId)
                .retrieve()
                .body(object : ParameterizedTypeReference<PaymentListResponse>() {})

            response?.data.orEmpty().map {
                PaymentSummary(id = it.id, seatId = it.seatId, status = it.status)
            }
        } catch (e: Exception) {
            logger.warn("Failed to list payments for user {}: {}", userId, e.message)
            emptyList()
        }
    }
}

data class PaymentSummary(
    val id: Long,
    val seatId: Long,
    val status: String
)

private data class PaymentListResponse(
    val status: String,
    val data: List<PaymentData>?,
    val message: String?,
    val code: String?
)

private data class PaymentData(
    val id: Long,
    val status: String,
    val seatId: Long,
    val userId: Long,
    val eventId: Long,
    val amount: Long,
    val method: String?
)

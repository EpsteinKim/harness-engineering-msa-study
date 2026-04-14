package com.epstein.practice.reserveservice.client

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

@Component
class PaymentClient(
    private val paymentServiceClient: RestClient
) {
    private val logger = LoggerFactory.getLogger(PaymentClient::class.java)

    fun processPayment(request: PaymentProcessRequest): PaymentProcessResult {
        return try {
            val response = paymentServiceClient.post()
                .uri("/api/v1/payments")
                .body(request)
                .retrieve()
                .body<PaymentServiceResponse>()

            val succeeded = response?.data?.status == "SUCCEEDED"
            PaymentProcessResult(
                success = succeeded,
                paymentId = response?.data?.id,
                message = response?.message ?: ""
            )
        } catch (e: HttpClientErrorException) {
            logger.warn("Payment service returned client error: {}", e.statusCode)
            PaymentProcessResult(success = false, message = "결제 요청이 거절되었습니다")
        } catch (e: ResourceAccessException) {
            logger.error("Payment service unreachable", e)
            PaymentProcessResult(success = false, message = "결제 서비스에 연결할 수 없습니다")
        }
    }
}

data class PaymentProcessRequest(
    val userId: Long,
    val seatId: Long,
    val eventId: Long,
    val amount: Long,
    val method: String
)

data class PaymentProcessResult(
    val success: Boolean,
    val paymentId: Long? = null,
    val message: String
)

private data class PaymentServiceResponse(
    val status: String,
    val data: PaymentData?,
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
    val method: String
)

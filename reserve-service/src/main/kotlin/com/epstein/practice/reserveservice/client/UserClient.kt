package com.epstein.practice.reserveservice.client

import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient

@Component
class UserClient(
    private val userServiceClient: RestClient
) {
    fun exists(userId: Long): Boolean =
        try {
            userServiceClient.get()
                .uri("/api/v1/users/{id}", userId)
                .retrieve()
                .toBodilessEntity()
                .statusCode
                .is2xxSuccessful
        } catch (e: HttpClientErrorException.NotFound) {
            false
        }
}

package com.epstein.practice.reserveservice.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
class RestClientConfig {

    @Bean
    fun userServiceClient(
        @Value("\${user-service.base-url}") baseUrl: String
    ): RestClient = RestClient.builder().baseUrl(baseUrl).build()

    @Bean
    fun paymentServiceClient(
        @Value("\${payment-service.base-url}") baseUrl: String
    ): RestClient = RestClient.builder().baseUrl(baseUrl).build()
}

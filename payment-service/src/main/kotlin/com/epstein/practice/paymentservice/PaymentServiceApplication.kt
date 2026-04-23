package com.epstein.practice.paymentservice

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(scanBasePackages = ["com.epstein.practice.paymentservice", "com.epstein.practice.common"])
@EntityScan(basePackages = ["com.epstein.practice.paymentservice", "com.epstein.practice.common"])
@EnableJpaRepositories(basePackages = ["com.epstein.practice.paymentservice", "com.epstein.practice.common"])
@EnableScheduling
class PaymentServiceApplication

fun main(args: Array<String>) {
    runApplication<PaymentServiceApplication>(*args)
}

package com.epstein.practice.reserveservice

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(scanBasePackages = ["com.epstein.practice.reserveservice", "com.epstein.practice.common"])
@EntityScan(basePackages = ["com.epstein.practice.reserveservice", "com.epstein.practice.common"])
@EnableJpaRepositories(basePackages = ["com.epstein.practice.reserveservice", "com.epstein.practice.common"])
@EnableScheduling
class ReserveApplication

fun main(args: Array<String>) {
    runApplication<ReserveApplication>(*args)
}

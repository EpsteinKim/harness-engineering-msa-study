package com.epstein.practice.coreservice

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(scanBasePackages = ["com.epstein.practice.coreservice", "com.epstein.practice.common"])
@EntityScan(basePackages = ["com.epstein.practice.coreservice", "com.epstein.practice.common"])
@EnableJpaRepositories(basePackages = ["com.epstein.practice.coreservice", "com.epstein.practice.common"])
@EnableScheduling
class CoreServiceApplication

fun main(args: Array<String>) {
    runApplication<CoreServiceApplication>(*args)
}

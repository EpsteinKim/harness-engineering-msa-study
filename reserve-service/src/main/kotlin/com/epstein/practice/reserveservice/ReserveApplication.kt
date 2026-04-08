package com.epstein.practice.reserveservice

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class ReserveApplication

fun main(args: Array<String>) {
    runApplication<ReserveApplication>(*args)
}

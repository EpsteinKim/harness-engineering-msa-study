package com.epstein.practice.reserveservice

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.epstein.practice.reserveservice", "com.epstein.practice.common"])
class ReserveApplication

fun main(args: Array<String>) {
    runApplication<ReserveApplication>(*args)
}

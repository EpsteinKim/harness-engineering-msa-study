package com.epstein.practice.reserveservice

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ReserveApplication

fun main(args: Array<String>) {
    runApplication<ReserveApplication>(*args)
}

package com.epstein.practice.queueservice

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class QueueApplication

fun main(args: Array<String>) {
    runApplication<QueueApplication>(*args)
}

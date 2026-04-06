package com.epstein.practice.harnessback

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class HarnessBackApplication

fun main(args: Array<String>) {
    runApplication<HarnessBackApplication>(*args)
}

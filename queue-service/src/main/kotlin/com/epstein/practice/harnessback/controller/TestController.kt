package com.epstein.practice.harnessback.controller

import org.osgi.annotation.bundle.Requirement
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/")
class TestController {
    @GetMapping
    fun hello(): String {
        return "Hello World"
    }
}
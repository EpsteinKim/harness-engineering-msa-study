package com.epstein.practice.userservice.controller

import com.epstein.practice.common.response.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/users")
class UserController {

    @GetMapping
    fun health(): ApiResponse<String> =
        ApiResponse.success(data = "user-service")
}

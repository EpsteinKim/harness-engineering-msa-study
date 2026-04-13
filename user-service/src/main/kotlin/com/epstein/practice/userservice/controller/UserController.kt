package com.epstein.practice.userservice.controller

import com.epstein.practice.common.response.ApiResponse
import com.epstein.practice.userservice.dto.UserResponse
import com.epstein.practice.userservice.dto.UserUpdateRequest
import com.epstein.practice.userservice.service.UserService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/users")
class UserController(
    private val userService: UserService
) {
    @GetMapping("/health")
    fun health(): ApiResponse<String> =
        ApiResponse.success(data = "user-service")

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): ApiResponse<UserResponse> =
        ApiResponse.success(data = UserResponse.from(userService.getById(id)))

    @PatchMapping("/{id}")
    fun update(
        @PathVariable id: Long,
        @RequestBody request: UserUpdateRequest
    ): ApiResponse<UserResponse> =
        ApiResponse.success(
            data = UserResponse.from(userService.update(id, request)),
            message = "사용자 정보가 수정되었습니다"
        )
}

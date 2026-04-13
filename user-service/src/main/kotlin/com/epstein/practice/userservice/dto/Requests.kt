package com.epstein.practice.userservice.dto

data class UserUpdateRequest(
    val email: String? = null,
    val name: String? = null
)

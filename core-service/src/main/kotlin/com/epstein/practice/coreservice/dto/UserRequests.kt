package com.epstein.practice.coreservice.dto

data class UserUpdateRequest(
    val email: String? = null,
    val name: String? = null
)

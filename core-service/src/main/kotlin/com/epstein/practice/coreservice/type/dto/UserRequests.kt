package com.epstein.practice.coreservice.type.dto

data class UserUpdateRequest(
    val email: String? = null,
    val name: String? = null
)

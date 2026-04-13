package com.epstein.practice.userservice.dto

import com.epstein.practice.userservice.entity.User

data class UserResponse(
    val id: Long,
    val email: String,
    val name: String
) {
    companion object {
        fun from(user: User) = UserResponse(user.id, user.email, user.name)
    }
}

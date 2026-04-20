package com.epstein.practice.coreservice.type.dto

import com.epstein.practice.coreservice.type.entity.User

data class UserResponse(
    val id: Long,
    val email: String,
    val name: String
) {
    companion object {
        fun from(user: User) = UserResponse(user.id, user.email, user.name)
    }
}

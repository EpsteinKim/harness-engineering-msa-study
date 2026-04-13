package com.epstein.practice.userservice.service

import com.epstein.practice.common.exception.ServerException
import com.epstein.practice.userservice.constant.ErrorCode
import com.epstein.practice.userservice.dto.UserUpdateRequest
import com.epstein.practice.userservice.entity.User
import com.epstein.practice.userservice.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserService(
    private val userRepository: UserRepository
) {
    fun getById(id: Long): User =
        userRepository.findById(id).orElseThrow {
            ServerException(message = "사용자를 찾을 수 없습니다", code = ErrorCode.USER_NOT_FOUND)
        }

    @Transactional
    fun update(id: Long, request: UserUpdateRequest): User {
        val user = getById(id)
        request.email?.let { user.email = it }
        request.name?.let { user.name = it }
        return userRepository.save(user)
    }
}

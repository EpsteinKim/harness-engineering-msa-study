package com.epstein.practice.coreservice.service

import com.epstein.practice.common.exception.ServerException
import com.epstein.practice.coreservice.constant.ErrorCode
import com.epstein.practice.coreservice.cache.UserCacheRepository
import com.epstein.practice.coreservice.dto.UserUpdateRequest
import com.epstein.practice.coreservice.entity.User
import com.epstein.practice.coreservice.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserService(
    private val userRepository: UserRepository,
    private val userCache: UserCacheRepository,
) {
    private val logger = LoggerFactory.getLogger(UserService::class.java)

    fun getById(id: Long): User =
        userRepository.findById(id).orElseThrow {
            ServerException(message = "사용자를 찾을 수 없습니다", code = ErrorCode.USER_NOT_FOUND)
        }

    @Transactional
    fun update(id: Long, request: UserUpdateRequest): User {
        val user = getById(id)
        request.email?.let { user.email = it }
        request.name?.let { user.name = it }
        val saved = userRepository.save(user)
        userCache.save(saved)
        logger.info("User updated + cache refreshed: id={}, version={}", saved.id, saved.version)
        return saved
    }

    /** 조회 시 캐시에 없는 경우 lazy hydration용 (reserve-service fallback) */
    @Transactional(readOnly = true)
    fun getByIdAndHydrate(id: Long): User {
        val user = getById(id)
        userCache.save(user)
        return user
    }
}

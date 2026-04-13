package com.epstein.practice.userservice.service

import com.epstein.practice.common.exception.ServerException
import com.epstein.practice.userservice.dto.UserUpdateRequest
import com.epstein.practice.userservice.entity.User
import com.epstein.practice.userservice.repository.UserRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class UserServiceTest {

    @Mock
    lateinit var userRepository: UserRepository

    private lateinit var userService: UserService

    @BeforeEach
    fun setUp() {
        userService = UserService(userRepository)
    }

    @Nested
    @DisplayName("getById - 사용자 조회")
    inner class GetById {

        @Test
        @DisplayName("성공 - 존재하는 ID로 조회")
        fun getByIdSuccess() {
            val user = User(id = 1L, email = "a@a.com", name = "Alice", password = "pw")
            `when`(userRepository.findById(1L)).thenReturn(Optional.of(user))

            val result = userService.getById(1L)

            assertEquals("Alice", result.name)
        }

        @Test
        @DisplayName("실패 - 존재하지 않는 ID면 USER_NOT_FOUND 예외")
        fun getByIdNotFound() {
            `when`(userRepository.findById(999L)).thenReturn(Optional.empty())

            val exception = assertThrows(ServerException::class.java) {
                userService.getById(999L)
            }
            assertEquals("USER_NOT_FOUND", exception.code)
        }
    }

    @Nested
    @DisplayName("update - 사용자 정보 수정")
    inner class Update {

        @Test
        @DisplayName("name만 전달하면 name만 변경된다")
        fun updateNameOnly() {
            val user = User(id = 1L, email = "a@a.com", name = "Alice", password = "pw")
            `when`(userRepository.findById(1L)).thenReturn(Optional.of(user))
            `when`(userRepository.save(user)).thenReturn(user)

            userService.update(1L, UserUpdateRequest(name = "Alice2"))

            assertEquals("Alice2", user.name)
            assertEquals("a@a.com", user.email)
        }

        @Test
        @DisplayName("email만 전달하면 email만 변경된다")
        fun updateEmailOnly() {
            val user = User(id = 1L, email = "a@a.com", name = "Alice", password = "pw")
            `when`(userRepository.findById(1L)).thenReturn(Optional.of(user))
            `when`(userRepository.save(user)).thenReturn(user)

            userService.update(1L, UserUpdateRequest(email = "new@a.com"))

            assertEquals("new@a.com", user.email)
            assertEquals("Alice", user.name)
        }
    }
}

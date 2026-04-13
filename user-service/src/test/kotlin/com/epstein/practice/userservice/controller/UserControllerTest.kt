package com.epstein.practice.userservice.controller

import com.epstein.practice.common.exception.GlobalExceptionHandler
import com.epstein.practice.common.exception.ServerException
import com.epstein.practice.userservice.constant.ErrorCode
import com.epstein.practice.userservice.dto.UserUpdateRequest
import com.epstein.practice.userservice.entity.User
import com.epstein.practice.userservice.service.UserService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders

@ExtendWith(MockitoExtension::class)
class UserControllerTest {

    @Mock
    lateinit var userService: UserService

    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(UserController(userService))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    @Nested
    @DisplayName("GET /api/v1/users/{id}")
    inner class GetUser {

        @Test
        @DisplayName("성공 - 사용자 정보 응답에 password 미노출")
        fun getSuccess() {
            `when`(userService.getById(1L)).thenReturn(
                User(id = 1L, email = "a@a.com", name = "Alice", password = "secret")
            )

            mockMvc.perform(get("/api/v1/users/1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.email").value("a@a.com"))
                .andExpect(jsonPath("$.data.name").value("Alice"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
        }

        @Test
        @DisplayName("실패 - USER_NOT_FOUND")
        fun getNotFound() {
            `when`(userService.getById(999L)).thenThrow(
                ServerException(message = "사용자를 찾을 수 없습니다", code = ErrorCode.USER_NOT_FOUND)
            )

            mockMvc.perform(get("/api/v1/users/999"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
        }
    }

    @Nested
    @DisplayName("PATCH /api/v1/users/{id}")
    inner class UpdateUser {

        @Test
        @DisplayName("성공 - 수정된 정보 응답")
        fun updateSuccess() {
            `when`(userService.update(1L, UserUpdateRequest(name = "Alice2"))).thenReturn(
                User(id = 1L, email = "a@a.com", name = "Alice2", password = "secret")
            )

            mockMvc.perform(
                patch("/api/v1/users/1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Alice2"}""")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.name").value("Alice2"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
        }
    }
}

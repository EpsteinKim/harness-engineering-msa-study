package com.epstein.practice.common.response

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiResponse<T>(
    val status: String,
    val data: T? = null,
    val message: String? = null,
    val code: String? = null,
) {
    companion object {
        fun <T> success(data: T? = null, message: String? = null): ApiResponse<T> =
            ApiResponse(status = "success", data = data, message = message)

        fun <T> error(message: String, code: String? = null): ApiResponse<T> =
            ApiResponse(status = "error", message = message, code = code)
    }
}

package com.epstein.practice.queueservice.controller

import com.epstein.practice.common.response.ApiResponse
import com.epstein.practice.queueservice.dto.EnqueueRequest
import com.epstein.practice.queueservice.dto.EnqueueResponse
import com.epstein.practice.queueservice.service.QueueService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/queues")
class QueueController(
    private val queueService: QueueService
) {
    @PostMapping("/enqueue")
    fun enqueue(@RequestBody request: EnqueueRequest): ApiResponse<EnqueueResponse> {
        try {
            queueService.enqueue(request.queueName, request.userId, request.callbackUrl, request.payload)
            return ApiResponse.success(
                data = EnqueueResponse(queueName = request.queueName, userId = request.userId)
            )
        } catch (e: IllegalArgumentException) {
            return ApiResponse.error(message = e.message ?: "Invalid request", code = "INVALID_CALLBACK")
        }
    }

    @DeleteMapping("/{queueName}/{userId}")
    fun cancel(@PathVariable queueName: String, @PathVariable userId: String): ApiResponse<String> {
        val cancelled = queueService.cancel(queueName, userId)
        if (!cancelled) {
            return ApiResponse.error(message = "User not found in queue", code = "QUEUE_NOT_FOUND")
        }
        return ApiResponse.success(data = userId, message = "Cancelled")
    }
}

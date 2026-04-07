package com.epstein.practice.queueservice.dto

data class EnqueueRequest(
    val queueName: String,
    val userId: String,
    val callbackUrl: String,
    val payload: Map<String, Any>
)

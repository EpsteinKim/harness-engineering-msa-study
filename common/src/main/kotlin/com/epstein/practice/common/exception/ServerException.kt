package com.epstein.practice.common.exception

data class ServerException(
    val data: Any? = null,
    override val message: String
) : RuntimeException(message)

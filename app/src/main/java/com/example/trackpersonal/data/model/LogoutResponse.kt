package com.example.trackpersonal.data.model

data class LogoutResponse(
    val success: Boolean?,
    val message: String?,
    val status_code: Int? = null,
    val status_message: String? = null
)

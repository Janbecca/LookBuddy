package com.lookbuddy.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    @SerialName("displayName") val displayName: String,
    val timezone: String,
)

@Serializable
data class TokenPairDto(
    @SerialName("accessToken") val accessToken: String,
    @SerialName("refreshToken") val refreshToken: String,
    val user: UserDto,
)

@Serializable data class LoginRequest(val email: String, val password: String)
@Serializable data class RegisterRequest(val email: String, val password: String, val displayName: String)
@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class StoredSession(val tokens: TokenPairDto, val epoch: String)
@Serializable data class PreferencesDto(
    val sedentaryMinutes: Int = 60, val breakMinutes: Int = 10, val busy: Boolean = false,
    val postureEnabled: Boolean = true, val companionEnabled: Boolean = true,
)

@Serializable
data class ReminderDto(
    val id: String,
    val category: String,
    val priority: Int,
    val status: String,
    val message: String,
    @SerialName("reasonCodes") val reasonCodes: List<String>,
)

@Serializable data class ReminderResponseRequest(val action: String, val snoozeMinutes: Int? = null)

@Serializable
data class DiaryDto(
    val id: String,
    @SerialName("date") val date: String,
    val status: String,
    val content: String,
    @SerialName("generatedBy") val generatedBy: String,
)


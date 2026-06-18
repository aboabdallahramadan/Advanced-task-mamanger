package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
)

@Serializable
data class RefreshRequest(
    val refreshToken: String,
)

@Serializable
data class LogoutRequest(
    val refreshToken: String,
)

@Serializable
data class AuthTokenUser(
    val id: String,
    val email: String,
    val timeZoneId: String,
)

@Serializable
data class AuthTokenResponse(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresIn: Int,
    val user: AuthTokenUser,
)

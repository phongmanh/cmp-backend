package com.example.api.auth

import com.example.api.user.UserResponse
import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
)

/**
 * [token] is the credential the provider's own SDK handed the app: an OpenID Connect id_token for
 * Google, an access token for Facebook.
 */
@Serializable
data class SocialSignInRequest(
    val provider: SocialProvider,
    val token: String,
)

@Serializable
data class RefreshTokenRequest(
    val refreshToken: String,
)

@Serializable
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    /** Lifetime of [accessToken] in seconds, counted from the moment the response was issued. */
    val expiresIn: Long,
    val user: UserResponse,
    val tokenType: String = "Bearer",
)

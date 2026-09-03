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

/**
 * Sent to `POST /api/v1/auth/password` by a signed-in user.
 *
 * [currentPassword] is proof that the person at the keyboard is the account owner and not somebody
 * who walked up to an unlocked device, so it is required even though the call already carries an
 * access token.
 */
@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
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

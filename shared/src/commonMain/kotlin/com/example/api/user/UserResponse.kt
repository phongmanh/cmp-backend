package com.example.api.user

import kotlinx.serialization.Serializable

/**
 * The only shape a user is ever published in. Password hashes and audit columns stay behind.
 */
@Serializable
data class UserResponse(
    val id: String,
    val email: String?,
    val displayName: String?,
    val avatarUrl: String?,
    val isEmailVerified: Boolean,
    /** ISO-8601 in UTC. */
    val createdAt: String,
    /** `SocialProvider.key` values; empty inside a token response, filled by `GET /users/me`. */
    val linkedProviders: List<String>,
)

package com.example.feature.user

import java.time.Instant
import java.util.UUID

data class User(
    val id: UUID,
    val email: String?,
    val displayName: String?,
    val avatarUrl: String?,
    val isEmailVerified: Boolean,
    val isActive: Boolean,
    val createdAt: Instant,
)

/**
 * Kept apart from [User] so a password hash can never travel with the object the routes serialize.
 */
data class UserCredentials(
    val userId: UUID,
    val passwordHash: String?,
    val isActive: Boolean,
)

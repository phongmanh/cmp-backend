package com.example.feature.user

import java.time.Instant
import java.util.UUID

data class User(
    val id: UUID,
    val email: String?,
    val displayName: String?,
    /** The address of a picture somebody else hosts. Null whenever [avatarImageId] is set. */
    val avatarUrl: String?,
    /** An image this server stores. Null whenever [avatarUrl] is set. */
    val avatarImageId: UUID?,
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

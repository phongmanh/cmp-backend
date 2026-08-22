package com.example.feature.user

import com.example.api.user.UserResponse

/**
 * The one crossing from the internal [User] to the shape published on the wire. Password hashes
 * and audit columns have no field to land in, so they cannot leak by accident.
 */
fun User.toResponse(linkedProviders: List<String> = emptyList()): UserResponse =
    UserResponse(
        id = id.toString(),
        email = email,
        displayName = displayName,
        avatarUrl = avatarUrl,
        isEmailVerified = isEmailVerified,
        createdAt = createdAt.toString(),
        linkedProviders = linkedProviders,
    )

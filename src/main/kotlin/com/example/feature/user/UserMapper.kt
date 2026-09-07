package com.example.feature.user

import com.example.api.user.UserResponse

/**
 * The one crossing from the internal [User] to the shape published on the wire. Password hashes
 * and audit columns have no field to land in, so they cannot leak by accident.
 *
 * [publicBaseUrl] arrives as an argument rather than being read here because turning a stored image
 * into an address a phone can reach is a question about this deployment, not about the user. The
 * routes hold the configuration and pass it in.
 */
fun User.toResponse(
    publicBaseUrl: String,
    linkedProviders: List<String> = emptyList(),
): UserResponse =
    UserResponse(
        id = id.toString(),
        email = email,
        displayName = displayName,
        avatarUrl = publishedAvatarUrl(publicBaseUrl, avatarImageId, avatarUrl),
        isEmailVerified = isEmailVerified,
        createdAt = createdAt.toString(),
        linkedProviders = linkedProviders,
    )

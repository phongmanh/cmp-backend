package com.example.feature.user

import com.example.common.ResourceNotFoundException
import java.util.UUID

class UserService(
    private val userRepository: UserRepository,
) {
    suspend fun profileOf(userId: UUID): UserProfile {
        val user = userRepository.findById(userId) ?: throw ResourceNotFoundException("User not found.")
        return UserProfile(user, userRepository.findLinkedProviders(userId))
    }

    /**
     * Both fields are replaced, so a null clears the stored value. The caller is always the owner:
     * the id comes from the verified token and never from the body.
     */
    suspend fun updateProfile(
        userId: UUID,
        displayName: String?,
        avatarUrl: String?,
    ): UserProfile {
        val user =
            userRepository.updateProfile(userId, displayName?.trim(), avatarUrl?.trim())
                ?: throw ResourceNotFoundException("User not found.")
        return UserProfile(user, userRepository.findLinkedProviders(userId))
    }
}

data class UserProfile(
    val user: User,
    val linkedProviders: List<String>,
)

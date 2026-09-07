package com.example.feature.image

import com.example.common.ResourceNotFoundException
import com.example.feature.user.UserProfile
import com.example.feature.user.UserService
import org.slf4j.LoggerFactory
import java.util.UUID

class ImageService(
    private val imageRepository: ImageRepository,
    private val userService: UserService,
) {
    private val logger = LoggerFactory.getLogger(ImageService::class.java)

    /**
     * Normalises the upload and makes it the account's avatar, replacing whatever was there.
     *
     * The caller is always the owner: [userId] comes from the verified token, and there is no field
     * anywhere in this path that could name a different account.
     */
    suspend fun replaceAvatar(
        userId: UUID,
        upload: ByteArray,
    ): UserProfile {
        val normalised = ImageDecoder.toAvatarJpeg(upload)
        val imageId =
            imageRepository.replaceAvatar(userId, normalised)
                ?: throw ResourceNotFoundException(USER_NOT_FOUND)

        // Identifiers only. The picture itself is personal data and has no business in a log line.
        logger.info("Stored avatar {} for user {} ({} bytes)", imageId, userId, normalised.bytes.size)
        return userService.profileOf(userId)
    }

    suspend fun removeAvatar(userId: UUID) {
        if (!imageRepository.clearAvatar(userId)) {
            throw ResourceNotFoundException(USER_NOT_FOUND)
        }
    }

    /**
     * An image is addressed by an id nobody can guess, and answering the same way for one that was
     * never issued and one that has been replaced is what keeps that true.
     */
    suspend fun imageOf(imageId: UUID): StoredImage =
        imageRepository.findById(imageId)
            ?: throw ResourceNotFoundException(IMAGE_NOT_FOUND)

    private companion object {
        const val USER_NOT_FOUND = "User not found."
        const val IMAGE_NOT_FOUND = "Image not found."
    }
}

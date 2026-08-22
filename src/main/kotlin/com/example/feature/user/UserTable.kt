package com.example.feature.user

import com.example.api.common.FieldLimits
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Shapes are owned by the Flyway migrations. These objects only describe the existing schema so
 * Exposed can build statements against it.
 */
object UserTable : UUIDTable("users") {
    val email = varchar("email", 320).nullable()
    val passwordHash = varchar("password_hash", 255).nullable()
    val displayName = varchar("display_name", 120).nullable()
    val avatarUrl = varchar("avatar_url", FieldLimits.MAX_AVATAR_URL_LENGTH).nullable()
    val isEmailVerified = bool("is_email_verified")
    val isActive = bool("is_active")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deletedAt = timestamp("deleted_at").nullable()
}

object UserIdentityTable : UUIDTable("user_identities") {
    val userId = reference("user_id", UserTable, onDelete = ReferenceOption.CASCADE)
    val provider = varchar("provider", 32)
    val providerUserId = varchar("provider_user_id", 191)
    val createdAt = timestamp("created_at")
}

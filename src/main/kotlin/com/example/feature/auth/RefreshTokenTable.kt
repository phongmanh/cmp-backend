package com.example.feature.auth

import com.example.feature.user.UserTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.timestamp

object RefreshTokenTable : UUIDTable("refresh_tokens") {
    val userId = reference("user_id", UserTable, onDelete = ReferenceOption.CASCADE)

    /** Every token minted from the same login shares a family, so one replay can revoke them all. */
    val familyId = uuid("family_id")

    /** Only the SHA-256 of the token is stored, so a database leak cannot be replayed. */
    val tokenHash = varchar("token_hash", 64)
    val expiresAt = timestamp("expires_at")
    val createdAt = timestamp("created_at")
    val revokedAt = timestamp("revoked_at").nullable()
}

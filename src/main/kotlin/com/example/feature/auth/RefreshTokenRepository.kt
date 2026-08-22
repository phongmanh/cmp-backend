package com.example.feature.auth

import com.example.common.dbQuery
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

data class StoredRefreshToken(
    val id: UUID,
    val userId: UUID,
    val familyId: UUID,
    val expiresAt: Instant,
    val revokedAt: Instant?,
)

class RefreshTokenRepository {
    suspend fun store(
        userId: UUID,
        familyId: UUID,
        tokenHash: String,
        expiresAt: Instant,
    ): UUID =
        dbQuery {
            RefreshTokenTable
                .insertAndGetId {
                    it[RefreshTokenTable.userId] = userId
                    it[RefreshTokenTable.familyId] = familyId
                    it[RefreshTokenTable.tokenHash] = tokenHash
                    it[RefreshTokenTable.expiresAt] = expiresAt
                    it[createdAt] = Instant.now()
                }.value
        }

    suspend fun findByHash(tokenHash: String): StoredRefreshToken? =
        dbQuery {
            RefreshTokenTable
                .selectAll()
                .where { RefreshTokenTable.tokenHash eq tokenHash }
                .singleOrNull()
                ?.let {
                    StoredRefreshToken(
                        id = it[RefreshTokenTable.id].value,
                        userId = it[RefreshTokenTable.userId].value,
                        familyId = it[RefreshTokenTable.familyId],
                        expiresAt = it[RefreshTokenTable.expiresAt],
                        revokedAt = it[RefreshTokenTable.revokedAt],
                    )
                }
        }

    /**
     * Retiring the used token and minting its successor is one write path, so a crash can never
     * leave the caller with two live tokens or none at all.
     */
    suspend fun rotate(
        currentId: UUID,
        userId: UUID,
        familyId: UUID,
        newTokenHash: String,
        expiresAt: Instant,
    ) {
        dbQuery {
            val now = Instant.now()
            RefreshTokenTable.update({ RefreshTokenTable.id eq currentId }) {
                it[revokedAt] = now
            }
            RefreshTokenTable.insert {
                it[RefreshTokenTable.userId] = userId
                it[RefreshTokenTable.familyId] = familyId
                it[tokenHash] = newTokenHash
                it[RefreshTokenTable.expiresAt] = expiresAt
                it[createdAt] = now
            }
        }
    }

    suspend fun revokeFamily(familyId: UUID) {
        dbQuery {
            RefreshTokenTable.update({
                (RefreshTokenTable.familyId eq familyId) and RefreshTokenTable.revokedAt.isNull()
            }) {
                it[revokedAt] = Instant.now()
            }
        }
    }

    suspend fun revokeAllForUser(userId: UUID) {
        dbQuery {
            RefreshTokenTable.update({
                (RefreshTokenTable.userId eq userId) and RefreshTokenTable.revokedAt.isNull()
            }) {
                it[revokedAt] = Instant.now()
            }
        }
    }
}

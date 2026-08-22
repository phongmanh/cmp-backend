package com.example.feature.user

import com.example.common.dbQuery
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

class UserRepository {
    suspend fun findById(id: UUID): User? =
        dbQuery {
            UserTable
                .selectAll()
                .where { (UserTable.id eq id) and UserTable.deletedAt.isNull() }
                .singleOrNull()
                ?.toUser()
        }

    suspend fun findByEmail(email: String): User? =
        dbQuery {
            UserTable
                .selectAll()
                .where { (UserTable.email eq email) and UserTable.deletedAt.isNull() }
                .singleOrNull()
                ?.toUser()
        }

    suspend fun findCredentialsByEmail(email: String): UserCredentials? =
        dbQuery {
            UserTable
                .selectAll()
                .where { (UserTable.email eq email) and UserTable.deletedAt.isNull() }
                .singleOrNull()
                ?.let {
                    UserCredentials(
                        userId = it[UserTable.id].value,
                        passwordHash = it[UserTable.passwordHash],
                        isActive = it[UserTable.isActive],
                    )
                }
        }

    suspend fun createWithPassword(
        email: String,
        passwordHash: String,
        displayName: String?,
        avatarUrl: String?,
    ): User =
        dbQuery {
            val now = Instant.now()
            val id =
                UserTable
                    .insertAndGetId {
                        it[UserTable.email] = email
                        it[UserTable.passwordHash] = passwordHash
                        it[UserTable.displayName] = displayName
                        it[UserTable.avatarUrl] = avatarUrl
                        it[isEmailVerified] = false
                        it[isActive] = true
                        it[createdAt] = now
                        it[updatedAt] = now
                    }.value
            User(id, email, displayName, avatarUrl, isEmailVerified = false, isActive = true, createdAt = now)
        }

    /**
     * Both rows are written in one transaction: a user without its identity row could never be
     * signed in again through the provider that created it.
     */
    suspend fun createWithIdentity(
        email: String?,
        displayName: String?,
        avatarUrl: String?,
        isEmailVerified: Boolean,
        provider: String,
        providerUserId: String,
    ): User =
        dbQuery {
            val now = Instant.now()
            val id =
                UserTable
                    .insertAndGetId {
                        it[UserTable.email] = email
                        it[passwordHash] = null
                        it[UserTable.displayName] = displayName
                        it[UserTable.avatarUrl] = avatarUrl
                        it[UserTable.isEmailVerified] = isEmailVerified
                        it[isActive] = true
                        it[createdAt] = now
                        it[updatedAt] = now
                    }.value

            UserIdentityTable.insert {
                it[userId] = id
                it[UserIdentityTable.provider] = provider
                it[UserIdentityTable.providerUserId] = providerUserId
                it[createdAt] = now
            }

            User(id, email, displayName, avatarUrl, isEmailVerified, isActive = true, createdAt = now)
        }

    suspend fun findByIdentity(
        provider: String,
        providerUserId: String,
    ): User? =
        dbQuery {
            (UserIdentityTable innerJoin UserTable)
                .selectAll()
                .where {
                    (UserIdentityTable.provider eq provider) and
                        (UserIdentityTable.providerUserId eq providerUserId) and
                        UserTable.deletedAt.isNull()
                }.singleOrNull()
                ?.toUser()
        }

    suspend fun linkIdentity(
        userId: UUID,
        provider: String,
        providerUserId: String,
    ) {
        dbQuery {
            UserIdentityTable.insert {
                it[UserIdentityTable.userId] = userId
                it[UserIdentityTable.provider] = provider
                it[UserIdentityTable.providerUserId] = providerUserId
                it[createdAt] = Instant.now()
            }
        }
    }

    /**
     * Used when a provider proves ownership of an address that an account claimed but never
     * verified. The password is dropped because whoever set it never proved they owned the
     * mailbox, and leaving it in place would hand them a way back into the real owner's account.
     */
    suspend fun claimUnverifiedAccount(userId: UUID) {
        dbQuery {
            UserTable.update({ UserTable.id eq userId }) {
                it[isEmailVerified] = true
                it[passwordHash] = null
                it[updatedAt] = Instant.now()
            }
        }
    }

    /**
     * Writes the editable half of the profile and reads the row back inside one transaction, so the
     * response can never describe a user a concurrent write has already moved past. A soft deleted
     * row matches nothing and reports back as missing.
     */
    suspend fun updateProfile(
        userId: UUID,
        displayName: String?,
        avatarUrl: String?,
    ): User? =
        dbQuery {
            val rowsWritten =
                UserTable.update({ (UserTable.id eq userId) and UserTable.deletedAt.isNull() }) {
                    it[UserTable.displayName] = displayName
                    it[UserTable.avatarUrl] = avatarUrl
                    it[updatedAt] = Instant.now()
                }

            if (rowsWritten == 0) {
                null
            } else {
                UserTable
                    .selectAll()
                    .where { UserTable.id eq userId }
                    .singleOrNull()
                    ?.toUser()
            }
        }

    suspend fun findLinkedProviders(userId: UUID): List<String> =
        dbQuery {
            UserIdentityTable
                .selectAll()
                .where { UserIdentityTable.userId eq userId }
                .map { it[UserIdentityTable.provider] }
        }
}

private fun ResultRow.toUser(): User =
    User(
        id = this[UserTable.id].value,
        email = this[UserTable.email],
        displayName = this[UserTable.displayName],
        avatarUrl = this[UserTable.avatarUrl],
        isEmailVerified = this[UserTable.isEmailVerified],
        isActive = this[UserTable.isActive],
        createdAt = this[UserTable.createdAt],
    )

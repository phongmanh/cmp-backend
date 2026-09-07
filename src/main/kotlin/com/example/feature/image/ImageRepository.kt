package com.example.feature.image

import com.example.common.dbQuery
import com.example.feature.user.UserTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

/**
 * The only place that knows an image is a row rather than an object in a bucket. Moving the bytes
 * to an object store replaces this class and leaves the routes, the service and the addresses
 * clients already hold exactly as they are.
 */
class ImageRepository {
    suspend fun findById(imageId: UUID): StoredImage? =
        dbQuery {
            ImageTable
                .selectAll()
                .where { ImageTable.id eq imageId }
                .singleOrNull()
                ?.let {
                    StoredImage(
                        id = it[ImageTable.id].value,
                        contentType = it[ImageTable.contentType],
                        sha256 = it[ImageTable.sha256],
                        bytes = it[ImageTable.bytes],
                    )
                }
        }

    /**
     * Stores [image], points the account at it, and removes whatever it pointed at before — all in
     * one transaction, which is what stops a half-done write from leaving an image nothing refers
     * to or an account referring to an image that was never written.
     *
     * The order inside matters. Repointing the account before deleting the old row is what keeps
     * the foreign key's `ON DELETE SET NULL` from clearing the pointer that was just set.
     *
     * Two uploads racing on one account cannot both win: the pool runs at REPEATABLE READ, so the
     * second transaction to reach the same `users` row fails rather than interleaving, and it takes
     * its own image insert down with it.
     *
     * Reports back `null` when there was no live account to write, so the caller can answer 404.
     */
    suspend fun replaceAvatar(
        ownerId: UUID,
        image: NewImage,
    ): UUID? =
        dbQuery {
            val account =
                UserTable
                    .selectAll()
                    .where { (UserTable.id eq ownerId) and UserTable.deletedAt.isNull() }
                    .singleOrNull()
                    ?: return@dbQuery null

            val previous = account[UserTable.avatarImageId]
            val stored =
                ImageTable
                    .insertAndGetId {
                        it[ImageTable.ownerId] = ownerId
                        it[contentType] = image.contentType
                        it[bytes] = image.bytes
                        it[byteSize] = image.bytes.size
                        it[width] = image.width
                        it[height] = image.height
                        it[sha256] = image.sha256
                        it[createdAt] = Instant.now()
                    }.value

            UserTable.update({ UserTable.id eq ownerId }) {
                it[avatarImageId] = stored
                // Cleared in the same statement: an account may hold a provider's URL or an image
                // of ours, never both, and the database enforces exactly that.
                it[avatarUrl] = null
                it[updatedAt] = Instant.now()
            }

            previous?.let { old -> ImageTable.deleteWhere { ImageTable.id eq old } }
            stored
        }

    /**
     * Drops the account's avatar however it was set, and deletes the stored image if there was one.
     * Reports back whether there was a live account to write.
     */
    suspend fun clearAvatar(ownerId: UUID): Boolean =
        dbQuery {
            val account =
                UserTable
                    .selectAll()
                    .where { (UserTable.id eq ownerId) and UserTable.deletedAt.isNull() }
                    .singleOrNull()
                    ?: return@dbQuery false

            val previous = account[UserTable.avatarImageId]

            UserTable.update({ UserTable.id eq ownerId }) {
                it[avatarImageId] = null
                it[avatarUrl] = null
                it[updatedAt] = Instant.now()
            }

            previous?.let { old -> ImageTable.deleteWhere { ImageTable.id eq old } }
            true
        }
}

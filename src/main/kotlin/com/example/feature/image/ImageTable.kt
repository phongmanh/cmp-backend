package com.example.feature.image

import com.example.feature.user.UserTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Shapes are owned by the Flyway migrations. This object only describes the existing schema so
 * Exposed can build statements against it.
 *
 * The reference runs one way only, from here to [UserTable]. The database has a foreign key in each
 * direction, but describing both in Kotlin would leave two `object`s each waiting on the other to
 * initialise, so `users.avatar_image_id` is declared as a plain column on [UserTable] instead.
 */
object ImageTable : UUIDTable("images") {
    val ownerId = reference("owner_id", UserTable, onDelete = ReferenceOption.CASCADE)
    val contentType = varchar("content_type", 64)
    val byteSize = integer("byte_size")
    val width = integer("width")
    val height = integer("height")
    val sha256 = varchar("sha256", 64)
    val bytes = binary("bytes")
    val createdAt = timestamp("created_at")
}

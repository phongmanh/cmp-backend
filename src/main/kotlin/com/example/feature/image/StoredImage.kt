package com.example.feature.image

import java.util.UUID

/**
 * An image that has been normalised and is ready to store, before it has an id.
 *
 * Deliberately not a data class: the generated `equals` would compare [bytes] by identity and read
 * as a value comparison, and the generated `toString` would put a JPEG in a log line.
 */
class NewImage(
    val contentType: String,
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val sha256: String,
)

/** A stored image on its way back out. Not a data class, for the reasons [NewImage] gives. */
class StoredImage(
    val id: UUID,
    val contentType: String,
    val sha256: String,
    val bytes: ByteArray,
)

package com.example.feature.customer

import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * A position in a customer list: the last row a page ended on.
 *
 * Lists run newest first, and [id] breaks the tie between customers created in the same
 * microsecond, so a page boundary never skips or repeats a row even while customers are being
 * added. That is what keyset pagination buys over an offset, which shifts under every insert.
 *
 * Opaque on the wire, so the ordering can change without a client noticing. A forged cursor gains
 * nothing: every query it feeds is still scoped to the caller's own customers.
 */
data class CustomerCursor(
    val createdAt: Instant,
    val id: UUID,
) {
    fun encode(): String = ENCODER.encodeToString("$createdAt$SEPARATOR$id".toByteArray(Charsets.UTF_8))

    companion object {
        private const val SEPARATOR = '|'
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()

        /** Null for anything this server did not produce. What to tell the caller is the route's call. */
        fun decode(raw: String): CustomerCursor? {
            val text = runCatching { String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8) }.getOrNull() ?: return null
            val parts = text.split(SEPARATOR)
            if (parts.size != 2) return null
            val createdAt = runCatching { Instant.parse(parts[0]) }.getOrNull() ?: return null
            val id = runCatching { UUID.fromString(parts[1]) }.getOrNull() ?: return null
            return CustomerCursor(createdAt, id)
        }
    }
}

package com.example.feature.customer

import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CustomerCursorTest {
    @Test
    fun `a cursor decodes to the position it was built from`() {
        val cursor = CustomerCursor(Instant.parse("2026-09-13T08:15:30.123456Z"), UUID.randomUUID())

        assertEquals(cursor, CustomerCursor.decode(cursor.encode()))
    }

    @Test
    fun `an encoded cursor is safe to put in a query string`() {
        val encoded = CustomerCursor(Instant.now(), UUID.randomUUID()).encode()

        assertEquals(encoded, encoded.filter { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun `anything the server did not produce decodes to null`() {
        val candidates =
            listOf(
                "",
                "not base64 at all!",
                encode("no separator"),
                encode("not-a-time|${UUID.randomUUID()}"),
                encode("2026-09-13T08:15:30Z|not-a-uuid"),
                encode("2026-09-13T08:15:30Z|${UUID.randomUUID()}|extra"),
            )

        candidates.forEach { assertNull(CustomerCursor.decode(it), "decoded '$it'") }
    }

    private fun encode(text: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
}

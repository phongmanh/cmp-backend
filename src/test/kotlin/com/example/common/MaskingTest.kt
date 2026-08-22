package com.example.common

import kotlin.test.Test
import kotlin.test.assertEquals

class MaskingTest {
    @Test
    fun `keeps the first character and the domain`() {
        assertEquals("u***@example.com", "user@example.com".maskEmail())
    }

    @Test
    fun `hides a value that is not an address`() {
        assertEquals("***", "not-an-address".maskEmail())
    }

    @Test
    fun `hides an address with no local part`() {
        assertEquals("***", "@example.com".maskEmail())
    }
}

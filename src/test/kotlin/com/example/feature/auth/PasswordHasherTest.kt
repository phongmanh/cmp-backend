package com.example.feature.auth

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PasswordHasherTest {
    private val hasher = PasswordHasher(cost = 4)

    @Test
    fun `accepts the password it hashed`() =
        runBlocking {
            val hash = hasher.hash("correct horse battery")

            assertTrue(hasher.verify("correct horse battery", hash))
        }

    @Test
    fun `rejects a different password`() =
        runBlocking {
            val hash = hasher.hash("correct horse battery")

            assertFalse(hasher.verify("incorrect horse battery", hash))
        }

    @Test
    fun `never stores the password in the hash`() =
        runBlocking {
            val password = "correct horse battery" // not-a-secret

            val hash = hasher.hash(password)

            assertFalse(hash.contains(password))
            assertNotEquals(password, hash)
        }

    @Test
    fun `same password hashes differently every time`() =
        runBlocking {
            assertNotEquals(hasher.hash("repeated password"), hasher.hash("repeated password"))
        }

    @Test
    fun `a malformed stored hash fails instead of throwing`() =
        runBlocking {
            assertFalse(hasher.verify("any password", "not-a-bcrypt-hash"))
        }
}

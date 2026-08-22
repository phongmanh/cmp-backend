package com.example.feature.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mindrot.jbcrypt.BCrypt
import java.util.UUID

/**
 * BCrypt is deliberately slow and CPU bound, so it runs on the default dispatcher rather than
 * blocking a request thread.
 */
class PasswordHasher(
    private val cost: Int,
) {
    /**
     * Generated once per process from a random value: comparing against it lets an unknown account
     * cost the same time as a wrong password, so accounts cannot be enumerated by timing.
     */
    private val decoyHash: String by lazy { BCrypt.hashpw(UUID.randomUUID().toString(), BCrypt.gensalt(cost)) }

    suspend fun hash(password: String): String = withContext(Dispatchers.Default) { BCrypt.hashpw(password, BCrypt.gensalt(cost)) }

    suspend fun verify(
        password: String,
        hash: String,
    ): Boolean =
        withContext(Dispatchers.Default) {
            runCatching { BCrypt.checkpw(password, hash) }.getOrDefault(false)
        }

    suspend fun burnTime(password: String) {
        withContext(Dispatchers.Default) { BCrypt.checkpw(password, decoyHash) }
    }
}

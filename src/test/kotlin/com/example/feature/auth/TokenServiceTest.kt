package com.example.feature.auth

import com.auth0.jwt.exceptions.JWTVerificationException
import com.example.common.JwtConfig
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TokenServiceTest {
    private fun tokenService(signingKey: String = "signing-key-a") =
        TokenService(
            JwtConfig(
                secret = signingKey,
                issuer = "com.example.test",
                audience = "com.example.test.client",
                realm = "test",
                accessTokenTtl = Duration.ofMinutes(15),
                refreshTokenTtl = Duration.ofDays(30),
            ),
        )

    @Test
    fun `access token carries the user id and passes its own verifier`() {
        val service = tokenService()
        val userId = UUID.randomUUID()

        val verified = service.accessTokenVerifier().verify(service.issueAccessToken(userId))

        assertEquals(userId.toString(), verified.subject)
        assertEquals("com.example.test", verified.issuer)
    }

    @Test
    fun `access token signed with a different key is rejected`() {
        val foreignToken = tokenService("signing-key-b").issueAccessToken(UUID.randomUUID())

        assertFailsWith<JWTVerificationException> {
            tokenService("signing-key-a").accessTokenVerifier().verify(foreignToken)
        }
    }

    @Test
    fun `access token expires within the configured window`() {
        val service = tokenService()

        val verified = service.accessTokenVerifier().verify(service.issueAccessToken(UUID.randomUUID()))
        val lifetimeSeconds = verified.expiresAtAsInstant.epochSecond - verified.issuedAtAsInstant.epochSecond

        assertEquals(service.accessTokenTtlSeconds, lifetimeSeconds)
    }

    @Test
    fun `each refresh token is unique and hashes to a stable digest`() {
        val service = tokenService()

        val first = service.generateRefreshToken()
        val second = service.generateRefreshToken()

        assertNotEquals(first, second)
        assertEquals(service.hashRefreshToken(first), service.hashRefreshToken(first))
        assertNotEquals(service.hashRefreshToken(first), service.hashRefreshToken(second))
    }

    @Test
    fun `refresh token hash does not contain the token itself`() {
        val service = tokenService()
        val token = service.generateRefreshToken()

        val hash = service.hashRefreshToken(token)

        assertEquals(64, hash.length)
        assertTrue(!hash.contains(token))
    }
}

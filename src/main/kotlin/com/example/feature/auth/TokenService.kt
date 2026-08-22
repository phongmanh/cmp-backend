package com.example.feature.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.example.common.JwtConfig
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

class TokenService(
    private val config: JwtConfig,
) {
    private val algorithm = Algorithm.HMAC256(config.secret)
    private val secureRandom = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    val accessTokenTtlSeconds: Long = config.accessTokenTtl.seconds

    fun accessTokenVerifier(): JWTVerifier =
        JWT
            .require(algorithm)
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .build()

    fun issueAccessToken(userId: UUID): String {
        val issuedAt = Instant.now()
        return JWT
            .create()
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withSubject(userId.toString())
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(Date.from(issuedAt))
            .withExpiresAt(Date.from(issuedAt.plus(config.accessTokenTtl)))
            .sign(algorithm)
    }

    /** Opaque, high entropy and never parsed by the client: it is only a lookup key for our store. */
    fun generateRefreshToken(): String {
        val bytes = ByteArray(REFRESH_TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    fun hashRefreshToken(token: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun refreshTokenExpiry(): Instant = Instant.now().plus(config.refreshTokenTtl)

    private companion object {
        const val REFRESH_TOKEN_BYTES = 32
    }
}

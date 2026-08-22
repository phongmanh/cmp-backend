package com.example.feature.auth.social

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.api.auth.SocialProvider
import com.example.common.AuthenticationException
import com.example.common.GoogleConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

/**
 * Verifies the OpenID Connect id_token produced by the Google sign-in SDK on the device. The
 * signing keys are fetched from Google's JWKS endpoint and cached, so a rotated key is picked up
 * without a deploy.
 */
class GoogleIdentityVerifier(
    config: GoogleConfig,
) : SocialIdentityVerifier {
    override val provider = SocialProvider.GOOGLE

    private val allowedAudiences = config.allowedAudiences.toTypedArray()

    private val jwkProvider =
        JwkProviderBuilder(URI(JWKS_URL).toURL())
            .cached(CACHED_KEYS, CACHE_HOURS, TimeUnit.HOURS)
            .rateLimited(RATE_LIMIT_CALLS, RATE_LIMIT_MINUTES, TimeUnit.MINUTES)
            .build()

    override suspend fun verify(token: String): SocialIdentity =
        withContext(Dispatchers.IO) {
            val keyId =
                runCatching { JWT.decode(token).keyId }.getOrNull()
                    ?: throw AuthenticationException(REJECTED)

            val publicKey =
                runCatching { jwkProvider.get(keyId).publicKey as RSAPublicKey }
                    .getOrElse { throw AuthenticationException(REJECTED) }

            val verified =
                runCatching {
                    JWT
                        .require(Algorithm.RSA256(publicKey, null))
                        .withIssuer(*ISSUERS)
                        .withAnyOfAudience(*allowedAudiences)
                        .acceptLeeway(CLOCK_LEEWAY_SECONDS)
                        .build()
                        .verify(token)
                }.getOrElse { throw AuthenticationException(REJECTED) }

            val subject = verified.subject?.takeIf { it.isNotBlank() } ?: throw AuthenticationException(REJECTED)

            SocialIdentity(
                provider = provider,
                providerUserId = subject,
                email = verified.getClaim("email").asString()?.takeIf { it.isNotBlank() },
                isEmailVerified = verified.getClaim("email_verified").asBoolean() ?: false,
                displayName = verified.getClaim("name").asString()?.takeIf { it.isNotBlank() },
                avatarUrl = verified.getClaim("picture").asString()?.takeIf { it.isNotBlank() },
            )
        }

    private companion object {
        const val JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs"
        const val REJECTED = "Google sign-in could not be verified."
        const val CLOCK_LEEWAY_SECONDS = 60L
        const val CACHED_KEYS = 10L
        const val CACHE_HOURS = 24L
        const val RATE_LIMIT_CALLS = 10L
        const val RATE_LIMIT_MINUTES = 1L
        val ISSUERS = arrayOf("https://accounts.google.com", "accounts.google.com")
    }
}

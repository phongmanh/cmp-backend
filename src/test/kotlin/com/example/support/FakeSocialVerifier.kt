package com.example.support

import com.example.api.auth.SocialProvider
import com.example.common.AuthenticationException
import com.example.feature.auth.social.SocialIdentity
import com.example.feature.auth.social.SocialIdentityVerifier

/**
 * Stands in for a provider so the tests never reach Google or Facebook over the network.
 */
class FakeSocialVerifier(
    override val provider: SocialProvider,
    private val identities: Map<String, SocialIdentity> = emptyMap(),
) : SocialIdentityVerifier {
    override suspend fun verify(token: String): SocialIdentity =
        identities[token] ?: throw AuthenticationException("Sign-in could not be verified.")
}

fun socialIdentity(
    provider: SocialProvider = SocialProvider.GOOGLE,
    providerUserId: String = uniqueProviderUserId(),
    email: String? = null,
    isEmailVerified: Boolean = true,
    displayName: String? = null,
    avatarUrl: String? = null,
): SocialIdentity = SocialIdentity(provider, providerUserId, email, isEmailVerified, displayName, avatarUrl)

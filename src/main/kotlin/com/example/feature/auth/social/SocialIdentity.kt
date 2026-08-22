package com.example.feature.auth.social

import com.example.api.auth.SocialProvider
import com.example.api.common.ErrorCode
import com.example.common.BusinessRuleException

/**
 * What a provider told us about the person signing in. [isEmailVerified] is the only claim that
 * lets us attach this login to an account that already exists.
 */
data class SocialIdentity(
    val provider: SocialProvider,
    val providerUserId: String,
    val email: String?,
    val isEmailVerified: Boolean,
    val displayName: String?,
    val avatarUrl: String?,
)

interface SocialIdentityVerifier {
    val provider: SocialProvider

    suspend fun verify(token: String): SocialIdentity
}

class SocialVerifierRegistry(
    verifiers: List<SocialIdentityVerifier>,
) {
    private val byProvider = verifiers.associateBy { it.provider }

    fun forProvider(provider: SocialProvider): SocialIdentityVerifier =
        byProvider[provider]
            ?: throw BusinessRuleException(
                code = ErrorCode.PROVIDER_NOT_ENABLED,
                message = "Sign in with ${provider.key} is not enabled.",
            )
}

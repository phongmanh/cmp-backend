package com.example.feature.auth.social

import com.example.api.auth.SocialProvider
import com.example.common.AuthenticationException
import com.example.common.FacebookConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Facebook hands the device an opaque access token rather than a signed id_token, so it cannot be
 * checked offline. The token is inspected server side through the Graph API, which also proves the
 * token was minted for this app and not for somebody else's.
 */
class FacebookIdentityVerifier(
    private val config: FacebookConfig,
    private val httpClient: HttpClient,
) : SocialIdentityVerifier {
    override val provider = SocialProvider.FACEBOOK

    override suspend fun verify(token: String): SocialIdentity {
        val debug = inspectToken(token)
        if (!debug.isValid || debug.appId != config.appId || debug.userId.isNullOrBlank()) {
            throw AuthenticationException(REJECTED)
        }

        val profile = fetchProfile(token)
        if (profile.id != debug.userId) throw AuthenticationException(REJECTED)

        return SocialIdentity(
            provider = provider,
            providerUserId = debug.userId,
            email = profile.email?.takeIf { it.isNotBlank() },
            // Facebook only ever discloses an address it has confirmed itself.
            isEmailVerified = !profile.email.isNullOrBlank(),
            displayName = profile.name?.takeIf { it.isNotBlank() },
            avatarUrl = profile.avatarUrl(),
        )
    }

    private suspend fun inspectToken(token: String): DebugTokenData {
        val response =
            httpClient.get("$GRAPH_BASE/debug_token") {
                parameter("input_token", token)
                parameter("access_token", "${config.appId}|${config.appSecret}")
            }
        return response.requireSuccess().body<DebugTokenEnvelope>().data
    }

    private suspend fun fetchProfile(token: String): FacebookProfile {
        val response =
            httpClient.get("$GRAPH_BASE/me") {
                parameter("fields", "id,name,email,picture.type(large)")
                parameter("access_token", token)
                parameter("appsecret_proof", appSecretProof(token))
            }
        return response.requireSuccess().body<FacebookProfile>()
    }

    /** Proves the call comes from our server and not from a leaked user token alone. */
    private fun appSecretProof(token: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(config.appSecret.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        return mac.doFinal(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun HttpResponse.requireSuccess(): HttpResponse {
        if (!status.isSuccess()) throw AuthenticationException(REJECTED)
        return this
    }

    @Serializable
    private data class DebugTokenEnvelope(
        val data: DebugTokenData = DebugTokenData(),
    )

    @Serializable
    private data class DebugTokenData(
        @SerialName("app_id") val appId: String? = null,
        @SerialName("is_valid") val isValid: Boolean = false,
        @SerialName("user_id") val userId: String? = null,
    )

    @Serializable
    private data class FacebookProfile(
        val id: String,
        val name: String? = null,
        val email: String? = null,
        val picture: FacebookPicture? = null,
    ) {
        /** The silhouette is the placeholder Facebook serves when nobody uploaded anything. */
        fun avatarUrl(): String? =
            picture
                ?.data
                ?.takeUnless { it.isSilhouette }
                ?.url
                ?.takeIf { it.isNotBlank() }
    }

    @Serializable
    private data class FacebookPicture(
        val data: FacebookPictureData? = null,
    )

    @Serializable
    private data class FacebookPictureData(
        val url: String? = null,
        @SerialName("is_silhouette") val isSilhouette: Boolean = false,
    )

    private companion object {
        const val GRAPH_BASE = "https://graph.facebook.com/v21.0"
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val REJECTED = "Facebook sign-in could not be verified."
    }
}

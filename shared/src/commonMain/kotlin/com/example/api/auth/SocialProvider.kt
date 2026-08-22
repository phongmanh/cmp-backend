package com.example.api.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The identity providers this API accepts. Unknown values are rejected by the deserializer rather
 * than defaulted to a provider.
 */
@Serializable
enum class SocialProvider {
    @SerialName("google")
    GOOGLE,

    @SerialName("facebook")
    FACEBOOK,
    ;

    /** The wire form, which is also the value that appears in `UserResponse.linkedProviders`. */
    val key: String get() = name.lowercase()

    companion object {
        fun fromKey(key: String): SocialProvider? = entries.firstOrNull { it.key == key }
    }
}

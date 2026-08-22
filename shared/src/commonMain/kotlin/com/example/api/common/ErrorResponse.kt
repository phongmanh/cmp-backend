package com.example.api.common

import kotlinx.serialization.Serializable

/**
 * The shape of every failure this API returns, whatever the status code.
 */
@Serializable
data class ErrorResponse(
    val code: String,
    val message: String,
)

/**
 * The [ErrorResponse.code] values a client is allowed to branch on.
 *
 * Deliberately strings rather than an enum: a client built against today's contract has to keep
 * working when the server starts returning a code it has never heard of, and a serialized enum
 * would fail to decode instead.
 */
object ErrorCode {
    const val VALIDATION_ERROR = "VALIDATION_ERROR"
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val UNAUTHENTICATED = "UNAUTHENTICATED"
    const val FORBIDDEN = "FORBIDDEN"
    const val NOT_FOUND = "NOT_FOUND"
    const val CONFLICT = "CONFLICT"
    const val PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"

    /** The deployment the app is talking to has no credentials for that social provider. */
    const val PROVIDER_NOT_ENABLED = "PROVIDER_NOT_ENABLED"
}

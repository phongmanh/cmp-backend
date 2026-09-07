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
    const val METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED"
    const val CONFLICT = "CONFLICT"
    const val PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"

    /** The deployment the app is talking to has no credentials for that social provider. */
    const val PROVIDER_NOT_ENABLED = "PROVIDER_NOT_ENABLED"

    /** The account signs in through a provider only, so there is no password to change. */
    const val PASSWORD_NOT_SET = "PASSWORD_NOT_SET"

    /**
     * The upload is not a picture this server will store: not a JPEG or a PNG, larger than it will
     * open, or too damaged to read. One code rather than three because the client does the same
     * thing about all of them — ask for a different file — and the message says which it was.
     */
    const val UNSUPPORTED_IMAGE = "UNSUPPORTED_IMAGE"
}

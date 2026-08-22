package com.example.api.common

/**
 * The bounds the server enforces on incoming fields.
 *
 * Shared so the app can refuse a value before it costs a round trip, and so the two sides cannot
 * disagree about where the line is. Only the limits are shared, not the messages: the server
 * writes English for an API consumer, the app writes something a person reads in their own
 * language.
 */
object FieldLimits {
    const val MAX_BODY_BYTES = 64 * 1024L

    const val MAX_EMAIL_LENGTH = 320

    const val MIN_PASSWORD_LENGTH = 8
    const val MAX_PASSWORD_LENGTH = 128

    /** BCrypt silently ignores everything past this many bytes, so a longer password is rejected outright. */
    const val MAX_PASSWORD_BYTES = 72

    const val MAX_DISPLAY_NAME_LENGTH = 120

    const val MAX_AVATAR_URL_LENGTH = 512

    /** Covers both a provider credential and one of our refresh tokens. */
    const val MAX_TOKEN_LENGTH = 8192

    val EMAIL_PATTERN = Regex("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$")

    /**
     * An avatar URL is handed straight to an image loader, so only an absolute `https` address is
     * accepted. A `javascript:` or `data:` value would turn a profile field into a way to run
     * something inside whatever renders it, and a plain `http` one would break the image on a page
     * the rest of which is encrypted.
     */
    val AVATAR_URL_PATTERN = Regex("^https://[^\\s<>\"]+$")
}

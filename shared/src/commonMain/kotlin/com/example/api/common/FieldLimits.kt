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

    /** The name of the one part `POST /api/v1/users/me/avatar` reads. Anything else is refused. */
    const val AVATAR_PART_NAME = "file"

    /** The uploaded file itself. */
    const val MAX_AVATAR_BYTES = 5 * 1024 * 1024L

    /**
     * The whole multipart request, which carries the boundary, the part headers and the epilogue on
     * top of the file. Kept apart from [MAX_AVATAR_BYTES] because checking the envelope against the
     * file's limit would refuse an upload of exactly the size we tell clients they may send.
     */
    const val MAX_AVATAR_REQUEST_BYTES = MAX_AVATAR_BYTES + 16 * 1024L

    /** The edge of the square every avatar is re-encoded to. */
    const val AVATAR_EDGE_PX = 512

    /**
     * The largest source the server will open. A decompression bomb is small on the wire and huge
     * in memory — 20000 x 20000 is under a megabyte of PNG and 1.6 GB decoded — so the header is
     * checked before any pixel is read.
     */
    const val MAX_AVATAR_SOURCE_EDGE_PX = 12_000

    /**
     * What an upload may be. The server decides by reading the file's leading bytes, never by
     * trusting this against a declared `Content-Type` or a filename, both of which the client
     * writes. Published so the app can refuse an obviously wrong pick before spending the upload.
     */
    val ALLOWED_AVATAR_UPLOAD_TYPES = listOf("image/jpeg", "image/png")

    val EMAIL_PATTERN = Regex("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$")

    /**
     * An avatar URL is handed straight to an image loader, so only an absolute `https` address is
     * accepted. A `javascript:` or `data:` value would turn a profile field into a way to run
     * something inside whatever renders it, and a plain `http` one would break the image on a page
     * the rest of which is encrypted.
     */
    val AVATAR_URL_PATTERN = Regex("^https://[^\\s<>\"]+$")
}

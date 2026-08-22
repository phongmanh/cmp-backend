package com.example.api.user

import kotlinx.serialization.Serializable

/**
 * The editable half of a profile, sent to `PUT /api/v1/users/me`.
 *
 * Replace semantics, not a patch: both fields are written exactly as they arrive, so `null` clears
 * the value and there is no way to say "leave this one alone". That is what keeps the request
 * unambiguous — a client that just read the profile already holds both values to send back.
 */
@Serializable
data class UpdateProfileRequest(
    val displayName: String? = null,
    val avatarUrl: String? = null,
)

package com.example.feature.user

import com.example.api.ApiRoutes
import com.example.api.common.FieldLimits
import java.util.UUID

/**
 * The single `avatarUrl` a client is given, from the two columns that can supply it.
 *
 * An uploaded image wins by construction rather than by preference: the two are mutually exclusive
 * in the database, so at most one of them is ever set.
 *
 * [publicBaseUrl] is where this deployment is reached from the outside, which the server cannot
 * work out for itself — behind a proxy the request it sees names the container, not the host the
 * app dialled. Note that the result is not held to [FieldLimits.AVATAR_URL_PATTERN]: that rule
 * governs what we accept from a client, and a local deployment legitimately publishes `http://`.
 */
fun publishedAvatarUrl(
    publicBaseUrl: String,
    avatarImageId: UUID?,
    storedAvatarUrl: String?,
): String? = avatarImageId?.let { "$publicBaseUrl${ApiRoutes.Images.byId(it.toString())}" } ?: storedAvatarUrl

/**
 * The gate for an avatar URL that never passed through request validation, which today means one a
 * social provider handed us during sign-up. Dropping a value we would refuse from a client keeps
 * the rule in one place, and dropping an over-long one keeps a provider from failing the insert
 * that creates the account.
 */
fun sanitizedAvatarUrl(raw: String?): String? {
    val candidate = raw?.trim().orEmpty()
    return candidate.takeIf {
        it.length <= FieldLimits.MAX_AVATAR_URL_LENGTH && FieldLimits.AVATAR_URL_PATTERN.matches(it)
    }
}

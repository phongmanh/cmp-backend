package com.example.feature.user

import com.example.api.common.FieldLimits

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

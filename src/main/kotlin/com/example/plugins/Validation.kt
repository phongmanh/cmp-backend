package com.example.plugins

import com.example.api.auth.LoginRequest
import com.example.api.auth.RefreshTokenRequest
import com.example.api.auth.RegisterRequest
import com.example.api.auth.SocialSignInRequest
import com.example.api.common.FieldLimits
import com.example.api.user.UpdateProfileRequest
import com.example.common.PayloadTooLargeException
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.plugins.requestvalidation.ValidationResult
import io.ktor.server.request.header

// The numbers live in the shared contract so the app can refuse the same values before spending a
// round trip on them. Only the wording below is server-side.
private const val MAX_BODY_BYTES = FieldLimits.MAX_BODY_BYTES
private const val MAX_EMAIL_LENGTH = FieldLimits.MAX_EMAIL_LENGTH
private const val MIN_PASSWORD_LENGTH = FieldLimits.MIN_PASSWORD_LENGTH
private const val MAX_PASSWORD_LENGTH = FieldLimits.MAX_PASSWORD_LENGTH
private const val MAX_PASSWORD_BYTES = FieldLimits.MAX_PASSWORD_BYTES
private const val MAX_DISPLAY_NAME_LENGTH = FieldLimits.MAX_DISPLAY_NAME_LENGTH
private const val MAX_AVATAR_URL_LENGTH = FieldLimits.MAX_AVATAR_URL_LENGTH
private const val MAX_TOKEN_LENGTH = FieldLimits.MAX_TOKEN_LENGTH

private val EMAIL_PATTERN = FieldLimits.EMAIL_PATTERN
private val AVATAR_URL_PATTERN = FieldLimits.AVATAR_URL_PATTERN

/**
 * Rejects an oversized body before it is read into memory.
 */
val BodySizeLimit =
    createApplicationPlugin("BodySizeLimit") {
        onCall { call ->
            val declaredLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
            if (declaredLength != null && declaredLength > MAX_BODY_BYTES) {
                throw PayloadTooLargeException("The request body is larger than the ${MAX_BODY_BYTES / 1024} KB limit.")
            }
        }
    }

fun Application.configureRequestValidation() {
    install(BodySizeLimit)

    install(RequestValidation) {
        validate<RegisterRequest> { request ->
            checks(
                emailProblems(request.email) +
                    passwordProblems(request.password) +
                    displayNameProblems(request.displayName) +
                    avatarUrlProblems(request.avatarUrl),
            )
        }

        // A null field is a deliberate "clear this", so only a value that is present is checked.
        validate<UpdateProfileRequest> { request ->
            checks(displayNameProblems(request.displayName) + avatarUrlProblems(request.avatarUrl))
        }

        // Login only checks that something usable was sent: a stricter policy would reject accounts
        // that were created before the policy changed.
        validate<LoginRequest> { request ->
            checks(
                presenceProblems(request.email, "email", MAX_EMAIL_LENGTH) +
                    presenceProblems(request.password, "password", MAX_PASSWORD_LENGTH),
            )
        }

        validate<SocialSignInRequest> { request ->
            checks(presenceProblems(request.token, "token", MAX_TOKEN_LENGTH))
        }

        validate<RefreshTokenRequest> { request ->
            checks(presenceProblems(request.refreshToken, "refreshToken", MAX_TOKEN_LENGTH))
        }
    }
}

private fun checks(problems: List<String>): ValidationResult =
    if (problems.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(problems)

private fun emailProblems(email: String): List<String> =
    when {
        email.isBlank() -> listOf("Email is required.")
        email.length > MAX_EMAIL_LENGTH -> listOf("Email must be at most $MAX_EMAIL_LENGTH characters.")
        !EMAIL_PATTERN.matches(email.trim()) -> listOf("Email is not a valid address.")
        else -> emptyList()
    }

private fun passwordProblems(password: String): List<String> =
    when {
        password.length < MIN_PASSWORD_LENGTH ->
            listOf("Password must be at least $MIN_PASSWORD_LENGTH characters.")
        password.length > MAX_PASSWORD_LENGTH ->
            listOf("Password must be at most $MAX_PASSWORD_LENGTH characters.")
        password.toByteArray(Charsets.UTF_8).size > MAX_PASSWORD_BYTES ->
            listOf("Password must be at most $MAX_PASSWORD_BYTES bytes.")
        else -> emptyList()
    }

private fun displayNameProblems(displayName: String?): List<String> =
    when {
        displayName == null -> emptyList()
        displayName.isBlank() -> listOf("Display name must not be blank.")
        displayName.length > MAX_DISPLAY_NAME_LENGTH ->
            listOf("Display name must be at most $MAX_DISPLAY_NAME_LENGTH characters.")
        else -> emptyList()
    }

private fun avatarUrlProblems(avatarUrl: String?): List<String> =
    when {
        avatarUrl == null -> emptyList()
        avatarUrl.isBlank() -> listOf("Avatar URL must not be blank.")
        avatarUrl.length > MAX_AVATAR_URL_LENGTH ->
            listOf("Avatar URL must be at most $MAX_AVATAR_URL_LENGTH characters.")
        !AVATAR_URL_PATTERN.matches(avatarUrl.trim()) ->
            listOf("Avatar URL must be an absolute https URL.")
        else -> emptyList()
    }

private fun presenceProblems(
    value: String,
    field: String,
    maxLength: Int,
): List<String> =
    when {
        value.isBlank() -> listOf("The $field is required.")
        value.length > maxLength -> listOf("The $field is longer than $maxLength characters.")
        else -> emptyList()
    }

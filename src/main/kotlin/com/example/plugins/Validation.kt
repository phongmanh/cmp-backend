package com.example.plugins

import com.example.api.ApiRoutes
import com.example.api.auth.ChangePasswordRequest
import com.example.api.auth.LoginRequest
import com.example.api.auth.RefreshTokenRequest
import com.example.api.auth.RegisterRequest
import com.example.api.auth.SocialSignInRequest
import com.example.api.common.FieldLimits
import com.example.api.customer.CustomerAddress
import com.example.api.customer.CustomerRequest
import com.example.api.user.UpdateProfileRequest
import com.example.common.PayloadTooLargeException
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.plugins.requestvalidation.ValidationResult
import io.ktor.server.request.header
import io.ktor.server.request.path
import java.util.Locale

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
private val PHONE_PATTERN = FieldLimits.PHONE_PATTERN

/** Every code that exists, which the shared pattern alone cannot say: `ZZ` has the right shape. */
private val ISO_COUNTRY_CODES: Set<String> = Locale.getISOCountries().toSet()

/**
 * Paths that carry something other than a JSON document and need their own ceiling.
 *
 * A lookup rather than a route-scoped plugin because this one runs before routing resolves, so it
 * has a path and never a matched route. The consequences are small and fail closed: a request whose
 * spelling differs from the constant — a different case, a trailing slash — still routes but misses
 * the exemption and is refused, which is the safe direction to be wrong in.
 */
private val EXEMPT_PATHS: Map<String, Long> =
    mapOf(ApiRoutes.Users.ME_AVATAR to FieldLimits.MAX_AVATAR_REQUEST_BYTES)

/**
 * Rejects an oversized body before it is read into memory.
 *
 * Only an early exit, not the whole defence: a chunked request declares no length at all, so
 * anything that reads a body of its own has to enforce its own limit as it streams.
 */
val BodySizeLimit =
    createApplicationPlugin("BodySizeLimit") {
        onCall { call ->
            val declaredLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull() ?: return@onCall
            val limit = EXEMPT_PATHS[call.request.path()] ?: MAX_BODY_BYTES
            if (declaredLength > limit) {
                throw PayloadTooLargeException("The request body is larger than the ${limit / 1024} KB limit.")
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

        // The current password is only checked for presence: an account may hold one that predates
        // today's policy, and rejecting it here would lock its owner out of fixing exactly that.
        validate<ChangePasswordRequest> { request ->
            checks(
                presenceProblems(request.currentPassword, "current password", MAX_PASSWORD_LENGTH) +
                    passwordProblems(request.newPassword, "New password") +
                    reusedPasswordProblems(request.currentPassword, request.newPassword),
            )
        }

        validate<SocialSignInRequest> { request ->
            checks(presenceProblems(request.token, "token", MAX_TOKEN_LENGTH))
        }

        validate<RefreshTokenRequest> { request ->
            checks(presenceProblems(request.refreshToken, "refreshToken", MAX_TOKEN_LENGTH))
        }

        // The same rules for a create and a replace. An optional field may be null, which clears it,
        // but never blank: an empty string is not a name, and storing one would read as a value.
        validate<CustomerRequest> { request ->
            checks(
                requiredTextProblems(request.firstName, "First name", FieldLimits.MAX_CUSTOMER_NAME_LENGTH) +
                    optionalTextProblems(request.lastName, "Last name", FieldLimits.MAX_CUSTOMER_NAME_LENGTH) +
                    optionalTextProblems(request.companyName, "Company name", FieldLimits.MAX_COMPANY_NAME_LENGTH) +
                    optionalEmailProblems(request.email) +
                    phoneProblems(request.phone) +
                    addressProblems(request.address) +
                    optionalTextProblems(request.notes, "Notes", FieldLimits.MAX_CUSTOMER_NOTES_LENGTH),
            )
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

private fun passwordProblems(
    password: String,
    label: String = "Password",
): List<String> =
    when {
        password.length < MIN_PASSWORD_LENGTH ->
            listOf("$label must be at least $MIN_PASSWORD_LENGTH characters.")
        password.length > MAX_PASSWORD_LENGTH ->
            listOf("$label must be at most $MAX_PASSWORD_LENGTH characters.")
        password.toByteArray(Charsets.UTF_8).size > MAX_PASSWORD_BYTES ->
            listOf("$label must be at most $MAX_PASSWORD_BYTES bytes.")
        else -> emptyList()
    }

private fun reusedPasswordProblems(
    currentPassword: String,
    newPassword: String,
): List<String> = if (currentPassword == newPassword) listOf("New password must differ from the current one.") else emptyList()

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

private fun requiredTextProblems(
    value: String,
    label: String,
    maxLength: Int,
): List<String> =
    when {
        value.isBlank() -> listOf("$label is required.")
        value.length > maxLength -> listOf("$label must be at most $maxLength characters.")
        else -> emptyList()
    }

private fun optionalTextProblems(
    value: String?,
    label: String,
    maxLength: Int,
): List<String> =
    when {
        value == null -> emptyList()
        value.isBlank() -> listOf("$label must not be blank.")
        value.length > maxLength -> listOf("$label must be at most $maxLength characters.")
        else -> emptyList()
    }

private fun optionalEmailProblems(email: String?): List<String> =
    when {
        email == null -> emptyList()
        email.isBlank() -> listOf("Email must not be blank.")
        else -> emailProblems(email)
    }

private fun phoneProblems(phone: String?): List<String> =
    when {
        phone == null -> emptyList()
        phone.isBlank() -> listOf("Phone must not be blank.")
        !PHONE_PATTERN.matches(phone.trim()) ->
            listOf("Phone must be in E.164 format: a +, the country code and the number, with no spaces.")
        else -> emptyList()
    }

private fun addressProblems(address: CustomerAddress?): List<String> {
    if (address == null) return emptyList()
    return requiredTextProblems(address.line1, "Address line 1", FieldLimits.MAX_ADDRESS_LINE_LENGTH) +
        optionalTextProblems(address.line2, "Address line 2", FieldLimits.MAX_ADDRESS_LINE_LENGTH) +
        requiredTextProblems(address.city, "City", FieldLimits.MAX_CITY_LENGTH) +
        optionalTextProblems(address.region, "Region", FieldLimits.MAX_REGION_LENGTH) +
        optionalTextProblems(address.postalCode, "Postal code", FieldLimits.MAX_POSTAL_CODE_LENGTH) +
        countryCodeProblems(address.countryCode)
}

private fun countryCodeProblems(countryCode: String): List<String> =
    if (countryCode.trim() in ISO_COUNTRY_CODES) {
        emptyList()
    } else {
        listOf("Country code must be an upper case ISO 3166-1 alpha-2 code, such as GB.")
    }

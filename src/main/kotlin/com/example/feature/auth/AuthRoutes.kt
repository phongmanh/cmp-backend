package com.example.feature.auth

import com.example.api.ApiRoutes
import com.example.api.auth.LoginRequest
import com.example.api.auth.RefreshTokenRequest
import com.example.api.auth.RegisterRequest
import com.example.api.auth.SocialProvider
import com.example.api.auth.SocialSignInRequest
import com.example.api.auth.TokenResponse
import com.example.common.BodyExample
import com.example.common.badRequest
import com.example.common.conflict
import com.example.common.internalError
import com.example.common.jsonBody
import com.example.common.payloadTooLarge
import com.example.common.requireUserId
import com.example.common.tooManyRequests
import com.example.common.unauthorized
import com.example.common.unprocessable
import com.example.feature.user.UserService
import com.example.feature.user.toResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ExperimentalKtorApi
import org.koin.ktor.ext.inject

const val AUTH_RATE_LIMIT = "auth"

private const val AUTH_TAG = "Auth"
private const val EMAIL = "ada@example.com"
private const val PASSWORD = "correct-horse-battery"
private const val GOOGLE_TOKEN = "eyJhbGciOiJSUzI1NiIsImtpZCI6IjBhZjc..."
private const val FACEBOOK_TOKEN = "EAAG7ZC1ZBk8kBO2ZC..."
private const val REFRESH_TOKEN = "8Kx2rQ7vT1nMcZp0aYbWl4gHsJdF6eRu9oPqXzNvB3s"
private const val AVATAR_URL = "https://cdn.example.com/avatars/ada.png"

/** The credential the provider's SDK handed the app, not anything this server issued. */
private fun socialExamples() =
    arrayOf(
        BodyExample("google", "Google id_token", SocialSignInRequest(SocialProvider.GOOGLE, GOOGLE_TOKEN)),
        BodyExample("facebook", "Facebook access token", SocialSignInRequest(SocialProvider.FACEBOOK, FACEBOOK_TOKEN)),
    )

private fun refreshExample() = BodyExample("stored", "A refresh token from an earlier response", RefreshTokenRequest(REFRESH_TOKEN))

private const val RATE_LIMIT_NOTE = "Rate limited to 10 requests per minute per client IP."

@OptIn(ExperimentalKtorApi::class)
fun Application.authRoutes() {
    val authService by inject<AuthService>()
    val userService by inject<UserService>()

    routing {
        rateLimit(RateLimitName(AUTH_RATE_LIMIT)) {
            post(ApiRoutes.Auth.REGISTER) {
                val request = call.receive<RegisterRequest>()
                val result =
                    authService.register(request.email, request.password, request.displayName, request.avatarUrl)
                call.response.headers.append(HttpHeaders.Location, ApiRoutes.Users.byId(result.user.id.toString()))
                call.respond(HttpStatusCode.Created, result.toResponse())
            }.describe {
                tag(AUTH_TAG)
                operationId = "register"
                summary = "Create an account with an email and password"
                requestBody {
                    jsonBody(
                        BodyExample("minimal", "Email and password only", RegisterRequest(EMAIL, PASSWORD)),
                        BodyExample(
                            "withProfile",
                            "With a display name and an avatar",
                            RegisterRequest(EMAIL, PASSWORD, "Ada Lovelace", AVATAR_URL),
                        ),
                    )
                }
                description =
                    """
                    Creates the account, issues a token pair, and returns the new user. The email is trimmed and
                    lowercased before it is stored, so `  Ada@Example.COM ` and `ada@example.com` are the same
                    account. A fresh account is never email-verified and has no linked providers.

                    $RATE_LIMIT_NOTE
                    """.trimIndent()
                responses {
                    HttpStatusCode.Created {
                        description = "The account was created and a token pair was issued."
                        headers {
                            header("Location") {
                                description = "Path of the user that was created."
                                required = true
                            }
                        }
                    }
                    badRequest()
                    conflict("An account with that email address already exists.")
                    payloadTooLarge()
                    tooManyRequests()
                    internalError()
                }
            }

            post(ApiRoutes.Auth.LOGIN) {
                val request = call.receive<LoginRequest>()
                val result = authService.login(request.email, request.password)
                call.respond(HttpStatusCode.OK, result.toResponse())
            }.describe {
                tag(AUTH_TAG)
                operationId = "login"
                summary = "Sign in with an email and password"
                requestBody {
                    jsonBody(BodyExample("credentials", "An email and password", LoginRequest(EMAIL, PASSWORD)))
                }
                description =
                    """
                    An unknown address and a wrong password return the same body and take the same time, so this
                    endpoint cannot be used to discover which addresses have accounts. A deactivated account
                    answers the same way.

                    $RATE_LIMIT_NOTE
                    """.trimIndent()
                responses {
                    HttpStatusCode.OK { description = "A fresh token pair." }
                    badRequest()
                    unauthorized("The email or the password is wrong, or the account is not active.")
                    payloadTooLarge()
                    tooManyRequests()
                    internalError()
                }
            }

            post(ApiRoutes.Auth.SOCIAL) {
                val request = call.receive<SocialSignInRequest>()
                val result = authService.signInWithProvider(request.provider, request.token)
                call.respond(HttpStatusCode.OK, result.toResponse())
            }.describe {
                tag(AUTH_TAG)
                operationId = "signInWithProvider"
                summary = "Sign in with Google or Facebook"
                requestBody { jsonBody(*socialExamples()) }
                description =
                    """
                    Exchanges a credential the provider's own SDK handed the app for a token pair of ours: a
                    Google OpenID Connect `id_token`, or a Facebook access token. The token is verified against
                    the provider on every call.

                    The account is resolved in this order:

                    1. An account already linked to that provider identity is signed in.
                    2. Otherwise, if the provider **verified** the email and an account holds that address, the
                       provider is linked to it and that account is signed in.
                    3. Otherwise a new account is created. An address the provider has not verified is not
                       stored, because it may belong to somebody else.

                    $RATE_LIMIT_NOTE
                    """.trimIndent()
                responses {
                    HttpStatusCode.OK {
                        description = "A fresh token pair, whether the account already existed or was just created."
                    }
                    badRequest("The token is blank or too long, or `provider` is not one of `google` / `facebook`.")
                    unauthorized("The provider rejected the token, or the matching account is not active.")
                    conflict("Two first-time sign-ins raced and neither could be settled. Retry.")
                    unprocessable("That provider is not configured on this deployment.")
                    payloadTooLarge()
                    tooManyRequests()
                    internalError()
                }
            }

            post(ApiRoutes.Auth.REFRESH) {
                val request = call.receive<RefreshTokenRequest>()
                val result = authService.refresh(request.refreshToken)
                call.respond(HttpStatusCode.OK, result.toResponse())
            }.describe {
                tag(AUTH_TAG)
                operationId = "refresh"
                summary = "Exchange a refresh token for a new pair"
                requestBody { jsonBody(refreshExample()) }
                description =
                    """
                    Returns a new access token **and a new refresh token**. Store the new refresh token and
                    discard the old one: it is retired by this call.

                    Sending a refresh token that was already retired is read as a leak. The whole token family
                    is revoked, this call fails with `401`, and the user has to sign in again.

                    $RATE_LIMIT_NOTE
                    """.trimIndent()
                responses {
                    HttpStatusCode.OK {
                        description = "A fresh pair. The refresh token in the response replaces the one you sent."
                    }
                    badRequest()
                    unauthorized("The refresh token is unknown, expired, already used, or its owner is not active.")
                    payloadTooLarge()
                    tooManyRequests()
                    internalError()
                }
            }
        }

        authenticate("auth-jwt") {
            post(ApiRoutes.Auth.LOGOUT) {
                val request = call.receive<RefreshTokenRequest>()
                authService.logout(call.requireUserId(), request.refreshToken)
                call.respond(HttpStatusCode.NoContent)
            }.describe {
                tag(AUTH_TAG)
                operationId = "logout"
                summary = "Sign out of one device"
                requestBody { jsonBody(refreshExample()) }
                description =
                    """
                    Revokes the refresh token's whole family, which ends that one login. Other devices keep
                    working.

                    Idempotent and deliberately silent: an unknown token, or one belonging to somebody else,
                    still answers `204`, so this endpoint cannot be used to probe which tokens exist. Access
                    tokens already issued stay valid until they expire.
                    """.trimIndent()
                responses {
                    HttpStatusCode.NoContent { description = "Done. No body." }
                    badRequest()
                    unauthorized()
                    payloadTooLarge()
                    internalError()
                }
            }

            post(ApiRoutes.Auth.LOGOUT_ALL) {
                authService.logoutEverywhere(call.requireUserId())
                call.respond(HttpStatusCode.NoContent)
            }.describe {
                tag(AUTH_TAG)
                operationId = "logoutEverywhere"
                summary = "Sign out of every device"
                description =
                    """
                    Revokes every refresh token the caller owns. Access tokens already issued stay valid until
                    they expire. Takes no body.
                    """.trimIndent()
                responses {
                    HttpStatusCode.NoContent { description = "Done. No body." }
                    unauthorized()
                    internalError()
                }
            }

            post(ApiRoutes.Auth.LINK) {
                val request = call.receive<SocialSignInRequest>()
                val userId = call.requireUserId()
                authService.linkProvider(userId, request.provider, request.token)
                val profile = userService.profileOf(userId)
                call.respond(HttpStatusCode.OK, profile.user.toResponse(profile.linkedProviders))
            }.describe {
                tag(AUTH_TAG)
                operationId = "linkProvider"
                summary = "Link a social provider to the signed-in account"
                requestBody { jsonBody(*socialExamples()) }
                description =
                    """
                    Attaches a Google or Facebook identity to the account the access token belongs to, so the
                    user can sign in either way afterwards. Linking an identity that is already on this account
                    succeeds and changes nothing.
                    """.trimIndent()
                responses {
                    HttpStatusCode.OK {
                        description = "The provider is linked. The body is the caller's updated profile."
                    }
                    badRequest("The token is blank or too long, or `provider` is not one of `google` / `facebook`.")
                    unauthorized("The access token is missing or invalid, or the provider rejected the social token.")
                    conflict("That provider identity is already linked to a different account.")
                    unprocessable("That provider is not configured on this deployment.")
                    payloadTooLarge()
                    internalError()
                }
            }
        }
    }
}

private fun AuthResult.toResponse(): TokenResponse =
    TokenResponse(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresIn = expiresInSeconds,
        user = user.toResponse(),
    )

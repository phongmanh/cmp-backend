package com.example.feature.user

import com.example.api.ApiRoutes
import com.example.api.user.UpdateProfileRequest
import com.example.common.BodyExample
import com.example.common.ResourceNotFoundException
import com.example.common.ValidationException
import com.example.common.badRequest
import com.example.common.internalError
import com.example.common.jsonBody
import com.example.common.notFound
import com.example.common.payloadTooLarge
import com.example.common.requireUserId
import com.example.common.unauthorized
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.ExperimentalKtorApi
import org.koin.ktor.ext.inject
import java.util.UUID

private const val USERS_TAG = "Users"
private const val AVATAR_URL = "https://cdn.example.com/avatars/ada.png"

@OptIn(ExperimentalKtorApi::class)
fun Application.userRoutes() {
    val userService by inject<UserService>()

    routing {
        authenticate("auth-jwt") {
            get(ApiRoutes.Users.ME) {
                val profile = userService.profileOf(call.requireUserId())
                call.respond(HttpStatusCode.OK, profile.user.toResponse(profile.linkedProviders))
            }.describe {
                tag(USERS_TAG)
                operationId = "getCurrentUser"
                summary = "Read the signed-in user's profile"
                description =
                    """
                    The user is read from the access token's subject. There is no way to ask for somebody
                    else's profile on this endpoint.
                    """.trimIndent()
                responses {
                    HttpStatusCode.OK { description = "The caller's profile." }
                    unauthorized()
                    notFound("The token is valid but the account behind it is gone.")
                    internalError()
                }
            }

            put(ApiRoutes.Users.ME) {
                val request = call.receive<UpdateProfileRequest>()
                val profile = userService.updateProfile(call.requireUserId(), request.displayName, request.avatarUrl)
                call.respond(HttpStatusCode.OK, profile.user.toResponse(profile.linkedProviders))
            }.describe {
                tag(USERS_TAG)
                operationId = "updateCurrentUser"
                summary = "Replace the signed-in user's display name and avatar"
                requestBody {
                    jsonBody(
                        BodyExample(
                            "full",
                            "A display name and an avatar",
                            UpdateProfileRequest("Ada Lovelace", AVATAR_URL),
                        ),
                        BodyExample("cleared", "Removes both values", UpdateProfileRequest()),
                    )
                }
                description =
                    """
                    Writes both fields exactly as they arrive, so a `null` clears the stored value rather
                    than leaving it alone. The account written is always the token's subject; an id in the
                    body would be ignored, and there is no field for one.

                    Email, verification state and linked providers are not editable here: each one is proof
                    of something a provider or a mailbox confirmed, so it changes only through the flow that
                    confirmed it. An avatar must be an absolute `https` URL.
                    """.trimIndent()
                responses {
                    HttpStatusCode.OK { description = "The profile as it now stands." }
                    badRequest()
                    unauthorized()
                    notFound("The token is valid but the account behind it is gone.")
                    payloadTooLarge()
                    internalError()
                }
            }

            get(ApiRoutes.Users.BY_ID) {
                val requested = call.userIdPathParameter()
                if (requested != call.requireUserId()) {
                    // Answered exactly like an id that was never issued, so this endpoint cannot be
                    // used to learn which accounts exist.
                    throw ResourceNotFoundException("User not found.")
                }
                val profile = userService.profileOf(requested)
                call.respond(HttpStatusCode.OK, profile.user.toResponse(profile.linkedProviders))
            }.describe {
                tag(USERS_TAG)
                operationId = "getUserById"
                summary = "Read a user's profile by id"
                description =
                    """
                    The address `POST /api/v1/auth/register` returns in its `Location` header. There is no
                    role that may read somebody else's profile, so an id that is not the token's subject
                    answers `404` rather than `403` — the two cases are deliberately indistinguishable.

                    Prefer `GET /api/v1/users/me`, which needs no id at all.
                    """.trimIndent()
                responses {
                    HttpStatusCode.OK { description = "The caller's profile." }
                    badRequest()
                    unauthorized()
                    notFound("No such user, or the id is not the one the token was issued for.")
                    internalError()
                }
            }
        }
    }
}

/**
 * Read from the path and nowhere else: [RoutingCall.parameters] merges the query string in, so a
 * `?userId=` could otherwise stand in for the segment the route matched.
 */
private fun RoutingCall.userIdPathParameter(): UUID {
    val raw = pathParameters[ApiRoutes.Users.USER_ID] ?: throw ValidationException("A user id is required.")
    return runCatching { UUID.fromString(raw) }
        .getOrElse { throw ValidationException("A user id must be a UUID.") }
}

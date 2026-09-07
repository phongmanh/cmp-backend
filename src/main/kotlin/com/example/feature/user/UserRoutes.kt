package com.example.feature.user

import com.example.api.ApiRoutes
import com.example.api.common.FieldLimits
import com.example.api.user.UpdateProfileRequest
import com.example.common.AppConfig
import com.example.common.BodyExample
import com.example.common.PayloadTooLargeException
import com.example.common.ResourceNotFoundException
import com.example.common.ValidationException
import com.example.common.badRequest
import com.example.common.internalError
import com.example.common.jsonBody
import com.example.common.notFound
import com.example.common.payloadTooLarge
import com.example.common.requireUserId
import com.example.common.tooManyRequests
import com.example.common.unauthorized
import com.example.common.unprocessable
import com.example.feature.image.ImageService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ExperimentalKtorApi
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import org.koin.ktor.ext.inject
import java.util.UUID

/** Named here rather than in the HTTP plugin, next to the one route it guards. */
const val UPLOAD_RATE_LIMIT = "upload"

/**
 * Lower than the auth limit: an upload costs a decode and a write, and nobody legitimately changes
 * their picture five times in a minute.
 */
const val UPLOAD_REQUESTS_PER_WINDOW = 5

private const val USERS_TAG = "Users"
private const val AVATAR_URL = "https://cdn.example.com/avatars/ada.png"

@OptIn(ExperimentalKtorApi::class)
fun Application.userRoutes() {
    val appConfig by inject<AppConfig>()
    val userService by inject<UserService>()
    val imageService by inject<ImageService>()

    routing {
        authenticate("auth-jwt") {
            get(ApiRoutes.Users.ME) {
                val profile = userService.profileOf(call.requireUserId())
                call.respond(HttpStatusCode.OK, profile.user.toResponse(appConfig.publicBaseUrl, profile.linkedProviders))
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
                call.respond(HttpStatusCode.OK, profile.user.toResponse(appConfig.publicBaseUrl, profile.linkedProviders))
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
                    confirmed it. An avatar sent here must be an absolute `https` URL somebody else hosts.

                    This replaces the avatar however it was set. An account that had uploaded one through
                    `POST /api/v1/users/me/avatar` gives it up on any call to this endpoint, and the stored
                    image is deleted rather than left unreachable.
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

            // The rate limiter sits under the path rather than above it. Wrapping the other way
            // would build a second `/api/v1/users/me` chain in the routing tree, and a request that
            // matched neither branch's method would then be answered as a path that does not exist
            // rather than as a method that is not allowed.
            //
            // Inside `authenticate`, because the limiter keys on the token's subject and only an
            // authentication that has already run can supply one.
            route(ApiRoutes.Users.ME_AVATAR) {
                // The limiter covers the upload and not the removal. Storing an avatar costs a
                // decode and a write and is worth rationing; dropping one is a single statement, and
                // sharing the bucket would leave somebody who had just changed their picture a few
                // times unable to take it down at all.
                //
                // It sits under the path rather than above it. Wrapping the other way would build a
                // second `/api/v1/users/me` chain in the routing tree, and a request matching
                // neither branch's method would then be answered as a path that does not exist
                // rather than as a method that is not allowed.
                //
                // Inside `authenticate`, because the limiter keys on the token's subject and only an
                // authentication that has already run can supply one.
                rateLimit(RateLimitName(UPLOAD_RATE_LIMIT)) {
                    post {
                        val profile = imageService.replaceAvatar(call.requireUserId(), call.receiveAvatarBytes())
                        call.respond(HttpStatusCode.OK, profile.user.toResponse(appConfig.publicBaseUrl, profile.linkedProviders))
                    }.describe {
                        tag(USERS_TAG)
                        operationId = "uploadCurrentUserAvatar"
                        summary = "Upload the signed-in user's avatar"
                        requestBody {
                            required = true
                            ContentType.MultiPart.FormData { }
                        }
                        description =
                            """
                            Send one `multipart/form-data` part named `${'$'}{FieldLimits.AVATAR_PART_NAME}`, holding a JPEG or a
                            PNG of at most ${'$'}{FieldLimits.MAX_AVATAR_BYTES / (1024 * 1024)} MB. The account written is always the token's subject.

                            What comes back is not what was sent. Every upload is decoded and re-encoded server side
                            into a ${'$'}{FieldLimits.AVATAR_EDGE_PX}×${'$'}{FieldLimits.AVATAR_EDGE_PX} JPEG, cropped from the centre rather than squashed. That
                            discards EXIF — the GPS coordinates a phone writes into a camera roll among it — and
                            anything else hidden behind a valid image header.

                            The format is decided by reading the file's leading bytes. The `Content-Type` on the part
                            and the filename are ignored, so neither can be used to smuggle another kind of file past.

                            Replaces whatever avatar the account had, a provider's URL included, and returns the
                            profile as it now stands. The previous image is deleted, so its address stops answering.

                            Rate limited to ${'$'}UPLOAD_REQUESTS_PER_WINDOW uploads per minute per account.
                            """.trimIndent()
                        responses {
                            HttpStatusCode.OK { description = "The profile, with `avatarUrl` pointing at the stored image." }
                            badRequest(
                                "The body is not multipart, or does not hold exactly one part named `${'$'}{FieldLimits.AVATAR_PART_NAME}`.",
                            )
                            unauthorized()
                            notFound("The token is valid but the account behind it is gone.")
                            payloadTooLarge()
                            unprocessable("The file is not a JPEG or a PNG, is larger than the server will open, or could not be read.")
                            tooManyRequests()
                            internalError()
                        }
                    }
                }

                delete {
                    imageService.removeAvatar(call.requireUserId())
                    call.respond(HttpStatusCode.NoContent)
                }.describe {
                    tag(USERS_TAG)
                    operationId = "deleteCurrentUserAvatar"
                    summary = "Remove the signed-in user's avatar"
                    description =
                        """
                        Leaves the account with no avatar at all, whether it was uploaded here or came from a
                        provider, and deletes the stored image if there was one. Succeeds on an account that
                        already has none, so a client can call it without checking first.
                        """.trimIndent()
                    responses {
                        HttpStatusCode.NoContent { description = "The account now has no avatar." }
                        unauthorized()
                        notFound("The token is valid but the account behind it is gone.")
                        internalError()
                    }
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
                call.respond(HttpStatusCode.OK, profile.user.toResponse(appConfig.publicBaseUrl, profile.linkedProviders))
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

/**
 * Reads the one file part of an avatar upload.
 *
 * The cap is enforced here as the request streams, not only by the `Content-Length` check that runs
 * before routing: a client sending `Transfer-Encoding: chunked` declares no length at all, so that
 * earlier check has nothing to look at and this is the one that has to hold.
 */
private suspend fun RoutingCall.receiveAvatarBytes(): ByteArray {
    // `receiveMultipart` answers a missing or wrong content type with an exception that is an
    // IOException rather than a bad-request one, which `StatusPages` would report as a 500. Asking
    // first turns the caller's mistake back into the 400 it is.
    if (request.contentType().withoutParameters() != ContentType.MultiPart.FormData) {
        throw ValidationException(SEND_ONE_FILE)
    }

    // Deliberately above our own limit. The parser enforces this one per part and reports it as a
    // bare IOException, so leaving room below it is what lets the check further down answer with a
    // documented 413 instead.
    val parts = receiveMultipart(formFieldLimit = FieldLimits.MAX_AVATAR_REQUEST_BYTES)
    var picture: ByteArray? = null

    while (true) {
        val part = parts.readPart() ?: break
        try {
            if (part !is PartData.FileItem || part.name != FieldLimits.AVATAR_PART_NAME || picture != null) {
                throw ValidationException(SEND_ONE_FILE)
            }
            picture = part.provider().readCapped(FieldLimits.MAX_AVATAR_BYTES)
        } finally {
            part.release()
        }
    }

    val bytes = picture ?: throw ValidationException(SEND_ONE_FILE)
    if (bytes.isEmpty()) {
        throw ValidationException("The ${FieldLimits.AVATAR_PART_NAME} part is empty.")
    }
    return bytes
}

/** Reads one byte past the limit and stops, so an oversized upload is refused rather than buffered. */
private suspend fun ByteReadChannel.readCapped(limit: Long): ByteArray {
    val read = readRemaining(limit + 1).readByteArray()
    if (read.size > limit) {
        throw PayloadTooLargeException("An avatar must be at most ${limit / (1024 * 1024)} MB.")
    }
    return read
}

private val SEND_ONE_FILE = "Send one multipart/form-data part named ${FieldLimits.AVATAR_PART_NAME}."

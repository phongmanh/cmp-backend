package com.example.feature.image

import com.example.api.ApiRoutes
import com.example.common.ValidationException
import com.example.common.badRequest
import com.example.common.internalError
import com.example.common.notFound
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.routing
import io.ktor.utils.io.ExperimentalKtorApi
import org.koin.ktor.ext.inject
import java.util.UUID

private const val IMAGES_TAG = "Images"

/**
 * A year, and immutable. Safe to promise because a row is never rewritten: changing an avatar
 * stores a new image at a new address, so a cached copy can never be out of date.
 */
private const val IMMUTABLE_FOR_A_YEAR = "public, max-age=31536000, immutable"

/**
 * Serving an image is the one thing this API does without a token.
 *
 * That is deliberate. The id is a random UUID and standing in for the credential is its whole job,
 * which is what lets an ordinary image loader fetch an avatar and a CDN cache it. Two things make
 * it safe to serve bytes a user supplied: they were re-encoded on the way in by [ImageDecoder], so
 * they are a JPEG this server wrote and nothing else, and `X-Content-Type-Options: nosniff` is set
 * on every response, so a browser will not go looking for a different type to treat them as.
 *
 * The cost is that a leaked address stays readable. Weighed against token plumbing in every image
 * loader and the loss of shared caching, and accepted.
 */
@OptIn(ExperimentalKtorApi::class)
fun Application.imageRoutes() {
    val imageService by inject<ImageService>()

    routing {
        get(ApiRoutes.Images.BY_ID) {
            val image = imageService.imageOf(call.imageIdPathParameter())
            val etag = "\"${image.sha256}\""

            call.response.header(HttpHeaders.ETag, etag)
            call.response.header(HttpHeaders.CacheControl, IMMUTABLE_FOR_A_YEAR)

            if (call.request.header(HttpHeaders.IfNoneMatch).matchesEtag(etag)) {
                call.respond(HttpStatusCode.NotModified)
                return@get
            }

            call.respondBytes(image.bytes, ContentType.parse(image.contentType), HttpStatusCode.OK)
        }.describe {
            tag(IMAGES_TAG)
            operationId = "getImage"
            summary = "Serve a stored image"
            description =
                """
                Public. The id is unguessable and is the only credential involved, so an image loader can
                fetch an avatar the way it fetches any other URL.

                The response may be cached for a year and never revalidated. An address always answers with
                the same bytes for as long as it answers at all: replacing an avatar stores a new image and
                retires the old one, so the id changes rather than the picture behind it. A retired id
                answers `404`, exactly as one that was never issued does.
                """.trimIndent()
            responses {
                HttpStatusCode.OK {
                    description = "The image. Always a JPEG today, whatever format it was uploaded in."
                    ContentType.Image.JPEG { }
                }
                HttpStatusCode.NotModified {
                    description = "The `If-None-Match` you sent still matches. No body."
                }
                badRequest("The id is not a UUID.")
                notFound("No image has that id, or the one that did has been replaced.")
                internalError()
            }
        }
    }
}

/**
 * Read from the path and nowhere else: [RoutingCall.parameters] merges the query string in, so a
 * `?imageId=` could otherwise stand in for the segment the route matched.
 */
private fun RoutingCall.imageIdPathParameter(): UUID {
    val raw = pathParameters[ApiRoutes.Images.IMAGE_ID] ?: throw ValidationException("An image id is required.")
    return runCatching { UUID.fromString(raw) }
        .getOrElse { throw ValidationException("An image id must be a UUID.") }
}

/**
 * `If-None-Match` is a list, and any member of it may be weak. Comparing the header to one tag
 * would miss `W/"abc"` and `"abc", "def"`, both of which a cache is entitled to send.
 */
private fun String?.matchesEtag(etag: String): Boolean {
    if (this == null) return false
    return split(',').any { candidate ->
        val trimmed = candidate.trim()
        trimmed == "*" || trimmed.removePrefix("W/") == etag
    }
}

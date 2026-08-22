package com.example.common

import com.example.api.common.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.ExampleObject
import io.ktor.openapi.GenericElement
import io.ktor.openapi.RequestBody
import io.ktor.openapi.Responses
import io.ktor.openapi.jsonSchema

/**
 * The error half of the documented contract, in one place, mirroring what `StatusPages` actually
 * returns. A route lists the failures it can produce; the body shape is never restated.
 */

fun Responses.Builder.badRequest(text: String = "A field broke a length, format or presence rule, or the body could not be read.") =
    errorResponse(HttpStatusCode.BadRequest, text)

fun Responses.Builder.unauthorized(text: String = "The access token is missing, malformed, expired, or signed by somebody else.") =
    errorResponse(HttpStatusCode.Unauthorized, text)

fun Responses.Builder.forbidden(text: String = "The token is valid but the caller may not touch this resource.") =
    errorResponse(HttpStatusCode.Forbidden, text)

fun Responses.Builder.notFound(text: String = "The resource does not exist.") = errorResponse(HttpStatusCode.NotFound, text)

fun Responses.Builder.conflict(text: String) = errorResponse(HttpStatusCode.Conflict, text)

fun Responses.Builder.payloadTooLarge(text: String = "The request body is over the 64 KB limit.") =
    errorResponse(HttpStatusCode.PayloadTooLarge, text)

fun Responses.Builder.unprocessable(text: String) = errorResponse(HttpStatusCode.UnprocessableEntity, text)

fun Responses.Builder.internalError(text: String = "Something went wrong. The detail is in the server log, never in the response.") =
    errorResponse(HttpStatusCode.InternalServerError, text)

/**
 * The rate limiter answers before `StatusPages` is reached, so this is the one failure that carries
 * no [ErrorResponse] body.
 */
fun Responses.Builder.tooManyRequests() {
    HttpStatusCode.TooManyRequests {
        description = "Over 10 requests in a minute from this client IP. Empty body; see the `Retry-After` header."
    }
}

private fun Responses.Builder.errorResponse(
    status: HttpStatusCode,
    text: String,
) {
    status {
        description = text
        schema = jsonSchema<ErrorResponse>()
    }
}

/** One worked request body, shown in the "Examples" dropdown above the editable payload. */
data class BodyExample<T>(
    val key: String,
    val summary: String,
    val value: T,
)

/**
 * A JSON request body whose schema and examples both come from [T]. The examples are real instances
 * rather than literal text, so renaming or removing a field breaks the build instead of quietly
 * leaving the documentation wrong.
 */
inline fun <reified T : Any> RequestBody.Builder.jsonBody(vararg examples: BodyExample<T>) {
    content {
        schema = jsonSchema<T>()
        examples.forEach { shown ->
            example(shown.key, ExampleObject(summary = shown.summary, value = GenericElement(shown.value)))
        }
    }
}

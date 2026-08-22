package com.example.plugins

import com.example.api.common.ErrorCode
import com.example.api.common.ErrorResponse
import com.example.common.AccessDeniedException
import com.example.common.AppException
import com.example.common.AuthenticationException
import com.example.common.BusinessRuleException
import com.example.common.ConflictException
import com.example.common.PayloadTooLargeException
import com.example.common.ResourceNotFoundException
import com.example.common.ValidationException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.requestvalidation.RequestValidationException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.SerializationException

/**
 * The single place an error becomes a response body. Route handlers throw and let this decide, so
 * every failure leaves the server in the one documented shape.
 */
fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<AppException> { call, cause ->
            call.respond(cause.status(), ErrorResponse(cause.code, cause.message))
        }

        exception<RequestValidationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorCode.VALIDATION_ERROR, cause.reasons.joinToString(" ")),
            )
        }

        // An unreadable body, or an enum value we do not accept, is the caller's mistake.
        exception<SerializationException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorCode.INVALID_REQUEST, "The request body is missing or malformed."),
            )
        }

        exception<BadRequestException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorCode.INVALID_REQUEST, "The request body is missing or malformed."),
            )
        }

        exception<Throwable> { call, cause ->
            // The detail belongs in the server log; the client gets nothing it could probe with.
            call.application.log.error("Unhandled failure while serving ${call.request.local.uri}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(ErrorCode.INTERNAL_ERROR, "Something went wrong."),
            )
        }
    }
}

private fun AppException.status(): HttpStatusCode =
    when (this) {
        is ValidationException -> HttpStatusCode.BadRequest
        is AuthenticationException -> HttpStatusCode.Unauthorized
        is AccessDeniedException -> HttpStatusCode.Forbidden
        is ResourceNotFoundException -> HttpStatusCode.NotFound
        is ConflictException -> HttpStatusCode.Conflict
        is PayloadTooLargeException -> HttpStatusCode.PayloadTooLarge
        is BusinessRuleException -> HttpStatusCode.UnprocessableEntity
    }

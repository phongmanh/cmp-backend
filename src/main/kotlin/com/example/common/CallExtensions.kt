package com.example.common

import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import java.util.UUID

/**
 * The caller identity comes from the verified token and nowhere else: a body, a query parameter or
 * a header carrying a user id is attacker controlled.
 */
fun ApplicationCall.requireUserId(): UUID {
    val subject =
        principal<JWTPrincipal>()?.subject
            ?: throw AuthenticationException("Missing or invalid access token.")
    return runCatching { UUID.fromString(subject) }
        .getOrElse { throw AuthenticationException("Missing or invalid access token.") }
}

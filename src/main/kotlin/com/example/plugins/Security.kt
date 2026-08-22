package com.example.plugins

import com.example.api.common.ErrorCode
import com.example.api.common.ErrorResponse
import com.example.common.JwtConfig
import com.example.feature.auth.TokenService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.respond
import org.koin.ktor.ext.inject
import java.util.UUID

fun Application.configureSecurity() {
    val jwtConfig by inject<JwtConfig>()
    val tokenService by inject<TokenService>()

    install(Authentication) {
        jwt("auth-jwt") {
            realm = jwtConfig.realm
            verifier(tokenService.accessTokenVerifier())

            validate { credential ->
                val subject = credential.payload.subject
                if (subject.isNullOrBlank() || runCatching { UUID.fromString(subject) }.isFailure) {
                    null
                } else {
                    JWTPrincipal(credential.payload)
                }
            }

            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse(ErrorCode.UNAUTHENTICATED, "Missing or invalid access token."),
                )
            }
        }
    }
}

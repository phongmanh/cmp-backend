package com.example.plugins

import com.example.api.ApiRoutes
import com.example.common.AppConfig
import com.example.common.DatabaseConfig
import com.example.common.JwtConfig
import com.example.common.SocialConfig
import com.example.support.TEST_PUBLIC_BASE_URL
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ALLOWED_ORIGIN = "https://allowed.example.com"

/** Deliberately not the server's own origin: `allowSameOrigin` would wave that one through. */
private const val UNKNOWN_ORIGIN = "https://unlisted.example.com"

/**
 * CORS is the one plugin whose misconfiguration is invisible from the server side: an origin that
 * is missing from `CORS_ALLOWED_ORIGINS` produces a bare 403 with no header, which reaches the
 * developer only as a blocked fetch in a browser console. These pin the whole path from the
 * configured origin list to the headers the browser actually reads.
 */
class CorsTest {
    @Test
    fun `answers a preflight from a configured origin`() =
        corsTestApplication(listOf(ALLOWED_ORIGIN)) {
            val response = preflight(ALLOWED_ORIGIN)

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ALLOWED_ORIGIN, response.headers[HttpHeaders.AccessControlAllowOrigin])
        }

    @Test
    fun `marks the real request as allowed for a configured origin`() =
        corsTestApplication(listOf(ALLOWED_ORIGIN)) {
            val response =
                client.post(ApiRoutes.Auth.LOGIN) {
                    header(HttpHeaders.Origin, ALLOWED_ORIGIN)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ALLOWED_ORIGIN, response.headers[HttpHeaders.AccessControlAllowOrigin])
        }

    @Test
    fun `allows the content type the json client sends`() =
        corsTestApplication(listOf(ALLOWED_ORIGIN)) {
            val response =
                preflight(ALLOWED_ORIGIN) {
                    header(HttpHeaders.AccessControlRequestHeaders, HttpHeaders.ContentType)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(
                response.headers[HttpHeaders.AccessControlAllowHeaders]
                    .orEmpty()
                    .contains(HttpHeaders.ContentType, ignoreCase = true),
                "expected Content-Type to be allowed so a JSON body survives the preflight",
            )
        }

    @Test
    fun `refuses a preflight from an origin that is not configured`() =
        corsTestApplication(listOf(ALLOWED_ORIGIN)) {
            val response = preflight(UNKNOWN_ORIGIN)

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
        }

    /** The state an unset `CORS_ALLOWED_ORIGINS` leaves behind: the cause of this bug. */
    @Test
    fun `refuses every origin when none are configured`() =
        corsTestApplication(emptyList()) {
            val response = preflight(ALLOWED_ORIGIN)

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
        }
}

private suspend fun ApplicationTestBuilder.preflight(
    origin: String,
    extraHeaders: HttpRequestBuilder.() -> Unit = {},
) = client.options(ApiRoutes.Auth.LOGIN) {
    header(HttpHeaders.Origin, origin)
    header(HttpHeaders.AccessControlRequestMethod, HttpMethod.Post.value)
    extraHeaders()
}

/**
 * Boots only the HTTP layer. The database backed [com.example.support.authTestApplication] would
 * need a container for a question that is settled entirely by the plugin configuration.
 */
private fun corsTestApplication(
    allowedOrigins: List<String>,
    block: suspend ApplicationTestBuilder.() -> Unit,
) {
    testApplication {
        environment { config = MapApplicationConfig() }

        application {
            install(Koin) {
                modules(module { single { corsAppConfig(allowedOrigins) } })
            }
            configureHttp()
            routing {
                post(ApiRoutes.Auth.LOGIN) {
                    call.respond(HttpStatusCode.OK)
                }
            }
        }

        block()
    }
}

/** `configureHttp` reads only the origin list and the proxy flag, so every other field stays empty. */
private fun corsAppConfig(allowedOrigins: List<String>): AppConfig =
    AppConfig(
        jwt =
            JwtConfig(
                secret = "",
                issuer = "",
                audience = "",
                realm = "",
                accessTokenTtl = Duration.ofMinutes(15),
                refreshTokenTtl = Duration.ofDays(30),
            ),
        database =
            DatabaseConfig(
                url = "",
                user = "",
                password = "",
                maxPoolSize = 1,
                shouldRunMigrations = false,
            ),
        social = SocialConfig(google = null, facebook = null),
        publicBaseUrl = TEST_PUBLIC_BASE_URL,
        allowedOrigins = allowedOrigins,
        bcryptCost = 4,
        shouldTrustProxyHeaders = false,
    )

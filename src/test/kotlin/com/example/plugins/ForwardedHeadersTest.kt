package com.example.plugins

import com.example.api.ApiRoutes
import com.example.common.AppConfig
import com.example.common.DatabaseConfig
import com.example.common.JwtConfig
import com.example.common.SocialConfig
import com.example.feature.auth.AUTH_RATE_LIMIT
import com.example.support.TEST_PUBLIC_BASE_URL
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
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

/** Matches `AUTH_REQUESTS_PER_WINDOW` in [configureHttp], which is private to that file. */
private const val REQUESTS_PER_WINDOW = 10

private const val FIRST_CALLER = "203.0.113.10"
private const val SECOND_CALLER = "203.0.113.20"

/**
 * The auth rate limiter keys on the caller's address. Behind a proxy every request arrives from the
 * proxy's own socket, so without `XForwardedHeaders` the whole internet shares one bucket and ten
 * sign-in attempts a minute lock out every user at once.
 *
 * Trusting the header is only safe where the proxy overwrites it. These pin both halves: the header
 * separates callers when the deployment opts in, and is ignored entirely when it does not.
 */
class ForwardedHeadersTest {
    @Test
    fun `keeps a separate bucket per client when proxy headers are trusted`() =
        rateLimitTestApplication(shouldTrustProxyHeaders = true) {
            repeat(REQUESTS_PER_WINDOW) {
                assertEquals(HttpStatusCode.OK, login(FIRST_CALLER).status)
            }

            assertEquals(
                HttpStatusCode.OK,
                login(SECOND_CALLER).status,
                "a second client must keep its own budget rather than inherit an exhausted one",
            )
        }

    @Test
    fun `still limits a single client when proxy headers are trusted`() =
        rateLimitTestApplication(shouldTrustProxyHeaders = true) {
            repeat(REQUESTS_PER_WINDOW) {
                assertEquals(HttpStatusCode.OK, login(FIRST_CALLER).status)
            }

            assertEquals(HttpStatusCode.TooManyRequests, login(FIRST_CALLER).status)
        }

    /**
     * The default, and the reason it is the default: reached directly, `X-Forwarded-For` is a value
     * the caller picks, so honouring it would hand anyone an unlimited supply of fresh buckets.
     */
    @Test
    fun `ignores a forged header when proxy headers are not trusted`() =
        rateLimitTestApplication(shouldTrustProxyHeaders = false) {
            repeat(REQUESTS_PER_WINDOW) {
                assertEquals(HttpStatusCode.OK, login(FIRST_CALLER).status)
            }

            assertEquals(
                HttpStatusCode.TooManyRequests,
                login(SECOND_CALLER).status,
                "changing the header must not reset the limit when the header is not trusted",
            )
        }
}

private suspend fun ApplicationTestBuilder.login(clientAddress: String): HttpResponse =
    client.post(ApiRoutes.Auth.LOGIN) {
        header("X-Forwarded-For", clientAddress)
    }

/**
 * Boots only the HTTP layer, for the same reason [CorsTest] does: the question is settled by plugin
 * configuration and needs no database.
 */
private fun rateLimitTestApplication(
    shouldTrustProxyHeaders: Boolean,
    block: suspend ApplicationTestBuilder.() -> Unit,
) {
    testApplication {
        environment { config = MapApplicationConfig() }

        application {
            install(Koin) {
                modules(module { single { rateLimitAppConfig(shouldTrustProxyHeaders) } })
            }
            configureHttp()
            routing {
                rateLimit(RateLimitName(AUTH_RATE_LIMIT)) {
                    post(ApiRoutes.Auth.LOGIN) {
                        call.respond(HttpStatusCode.OK)
                    }
                }
            }
        }

        block()
    }
}

/** `configureHttp` reads only the origin list and the proxy flag, so every other field stays empty. */
private fun rateLimitAppConfig(shouldTrustProxyHeaders: Boolean): AppConfig =
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
        allowedOrigins = emptyList(),
        bcryptCost = 4,
        shouldTrustProxyHeaders = shouldTrustProxyHeaders,
    )

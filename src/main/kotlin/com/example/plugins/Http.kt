package com.example.plugins

import com.example.common.AppConfig
import com.example.feature.auth.AUTH_RATE_LIMIT
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.hsts.HSTS
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import org.koin.ktor.ext.inject
import kotlin.time.Duration.Companion.minutes

fun Application.configureHttp() {
    val appConfig by inject<AppConfig>()

    // The rate limiter keys on the caller's address, which behind a proxy is the proxy's own.
    // Reading it from X-Forwarded-For is only safe where the proxy overwrites that header rather
    // than passing a client-supplied one through, so it stays off unless the deployment says so.
    if (appConfig.shouldTrustProxyHeaders) {
        install(XForwardedHeaders)
    }

    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        header("Referrer-Policy", "no-referrer")
    }

    install(HSTS) {
        includeSubDomains = true
    }

    install(CORS) {
        // Exact origins only. A wildcard host would let any site spend a user's tokens.
        appConfig.allowedOrigins.forEach { origin ->
            val scheme = origin.substringBefore("://", "https")
            val host = origin.substringAfter("://")
            allowHost(host, schemes = listOf(scheme))
        }
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
    }

    install(RateLimit) {
        register(RateLimitName(AUTH_RATE_LIMIT)) {
            rateLimiter(limit = AUTH_REQUESTS_PER_WINDOW, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteAddress }
        }
    }
}

private const val AUTH_REQUESTS_PER_WINDOW = 10

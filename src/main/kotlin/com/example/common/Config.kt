package com.example.common

import io.ktor.server.config.ApplicationConfig
import java.time.Duration

data class JwtConfig(
    val secret: String,
    val issuer: String,
    val audience: String,
    val realm: String,
    val accessTokenTtl: Duration,
    val refreshTokenTtl: Duration,
)

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int,
    val shouldRunMigrations: Boolean,
)

data class GoogleConfig(
    val allowedAudiences: List<String>,
)

data class FacebookConfig(
    val appId: String,
    val appSecret: String,
)

/**
 * A provider is enabled only when its configuration is present, so the service can run with a
 * subset of providers. A half-configured provider is a deployment mistake and fails fast instead.
 */
data class SocialConfig(
    val google: GoogleConfig?,
    val facebook: FacebookConfig?,
)

data class AppConfig(
    val jwt: JwtConfig,
    val database: DatabaseConfig,
    val social: SocialConfig,
    /** Absolute, no trailing slash. Every avatar address the API publishes is built on it. */
    val publicBaseUrl: String,
    val allowedOrigins: List<String>,
    val bcryptCost: Int,
    val shouldTrustProxyHeaders: Boolean,
)

fun ApplicationConfig.toAppConfig(): AppConfig =
    AppConfig(
        jwt =
            JwtConfig(
                secret = required("jwt.secret", "JWT_SECRET"),
                issuer = required("jwt.issuer", "JWT_ISSUER"),
                audience = required("jwt.audience", "JWT_AUDIENCE"),
                realm = required("jwt.realm", "JWT_REALM"),
                accessTokenTtl = Duration.ofMinutes(int("jwt.accessTokenMinutes", 15).toLong()),
                refreshTokenTtl = Duration.ofDays(int("jwt.refreshTokenDays", 30).toLong()),
            ),
        database =
            DatabaseConfig(
                url = required("database.url", "DATABASE_URL"),
                user = required("database.user", "DATABASE_USER"),
                password = required("database.password", "DATABASE_PASSWORD"),
                maxPoolSize = int("database.maxPoolSize", 10),
                shouldRunMigrations = boolean("database.shouldRunMigrations", true),
            ),
        social = socialConfig(),
        publicBaseUrl = publicBaseUrl(),
        allowedOrigins = list("security.allowedOrigins"),
        bcryptCost = int("security.bcryptCost", 12),
        shouldTrustProxyHeaders = boolean("security.trustProxyHeaders", false),
    )

private fun ApplicationConfig.socialConfig(): SocialConfig {
    val googleAudiences = list("social.google.clientIds")
    val facebookAppId = optional("social.facebook.appId")
    val facebookAppSecret = optional("social.facebook.appSecret")

    if ((facebookAppId == null) != (facebookAppSecret == null)) {
        error(
            "Facebook is half configured. Set both FACEBOOK_APP_ID and FACEBOOK_APP_SECRET, " +
                "or neither to disable the provider.",
        )
    }

    return SocialConfig(
        google = googleAudiences.takeIf { it.isNotEmpty() }?.let { GoogleConfig(it) },
        facebook =
            if (facebookAppId != null && facebookAppSecret != null) {
                FacebookConfig(facebookAppId, facebookAppSecret)
            } else {
                null
            },
    )
}

/**
 * Where this deployment is reached from the outside.
 *
 * Required, and checked rather than merely read. The server cannot work this out for itself —
 * behind a proxy the request it sees names the container, not the host the app dialled — and a
 * wrong value fails silently in a way nothing else here does: the service is healthy, every
 * response is well formed, and every avatar in the app is a broken image.
 */
private fun ApplicationConfig.publicBaseUrl(): String {
    val raw = required("app.publicBaseUrl", "PUBLIC_BASE_URL")

    if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
        error("Configuration 'app.publicBaseUrl' must be an absolute http or https URL. Set PUBLIC_BASE_URL.")
    }
    if (raw.endsWith("/")) {
        error("Configuration 'app.publicBaseUrl' must not end with a slash. Set PUBLIC_BASE_URL.")
    }
    return raw
}

private fun ApplicationConfig.optional(path: String): String? = propertyOrNull(path)?.getString()?.takeIf { it.isNotBlank() }

private fun ApplicationConfig.required(
    path: String,
    envVar: String,
): String =
    optional(path)
        ?: error("Missing required configuration '$path'. Set the $envVar environment variable.")

private fun ApplicationConfig.int(
    path: String,
    default: Int,
): Int {
    val raw = optional(path) ?: return default
    return raw.toIntOrNull() ?: error("Configuration '$path' must be a whole number.")
}

private fun ApplicationConfig.boolean(
    path: String,
    default: Boolean,
): Boolean {
    val raw = optional(path) ?: return default
    return raw.toBooleanStrictOrNull() ?: error("Configuration '$path' must be true or false.")
}

private fun ApplicationConfig.list(path: String): List<String> =
    optional(path)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()

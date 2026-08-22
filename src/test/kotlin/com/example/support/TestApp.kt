package com.example.support

import com.example.common.AppConfig
import com.example.common.toAppConfig
import com.example.configureSerialization
import com.example.feature.auth.authRoutes
import com.example.feature.auth.social.SocialIdentityVerifier
import com.example.feature.auth.social.SocialVerifierRegistry
import com.example.feature.user.userRoutes
import com.example.plugins.appModule
import com.example.plugins.configureDatabase
import com.example.plugins.configureDocs
import com.example.plugins.configureHttp
import com.example.plugins.configureRequestValidation
import com.example.plugins.configureSecurity
import com.example.plugins.configureStatusPages
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.util.UUID

/** Obviously fake, and only ever read by tests running against a throwaway container. */
private const val TEST_SIGNING_KEY = "test-signing-key-for-unit-tests-only"

fun testAppConfig(): AppConfig {
    val container = TestPostgres.container
    return MapApplicationConfig(
        "jwt.secret" to TEST_SIGNING_KEY,
        "jwt.issuer" to "com.example.test",
        "jwt.audience" to "com.example.test.client",
        "jwt.realm" to "test",
        "jwt.accessTokenMinutes" to "15",
        "jwt.refreshTokenDays" to "30",
        "database.url" to container.jdbcUrl,
        "database.user" to container.username,
        "database.password" to container.password,
        "database.maxPoolSize" to "4",
        "database.shouldRunMigrations" to "true",
        // Cheap on purpose: the cost factor is a production concern, not a test one.
        "security.bcryptCost" to "4",
    ).toAppConfig()
}

/**
 * Boots the real application wiring against a throwaway Postgres. Skipped rather than failed when
 * there is no Docker daemon to run the container on.
 */
fun authTestApplication(
    verifiers: List<SocialIdentityVerifier> = emptyList(),
    block: suspend ApplicationTestBuilder.() -> Unit,
) {
    assumeTrue(TestPostgres.isDockerAvailable, "Docker is not available, skipping database backed test")

    testApplication {
        environment { config = MapApplicationConfig() }

        application {
            install(Koin) {
                modules(
                    appModule(testAppConfig()),
                    module { single { SocialVerifierRegistry(verifiers) } },
                )
            }
            configureDatabase()
            configureHttp()
            configureSecurity()
            configureSerialization()
            configureStatusPages()
            configureRequestValidation()
            configureDocs()
            authRoutes()
            userRoutes()
        }

        block()
    }
}

fun ApplicationTestBuilder.jsonClient(): HttpClient =
    createClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

fun uniqueEmail(): String = "user-${UUID.randomUUID()}@example.com"

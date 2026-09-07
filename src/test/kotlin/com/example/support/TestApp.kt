package com.example.support

import com.example.common.AppConfig
import com.example.common.createDataSource
import com.example.common.runMigrations
import com.example.common.toAppConfig
import com.example.configureSerialization
import com.example.feature.auth.authRoutes
import com.example.feature.auth.social.SocialIdentityVerifier
import com.example.feature.auth.social.SocialVerifierRegistry
import com.example.feature.image.imageRoutes
import com.example.feature.user.userRoutes
import com.example.plugins.appModule
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
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.util.UUID

/** Obviously fake, and only ever read by tests running against a throwaway container. */
private const val TEST_SIGNING_KEY = "test-signing-key-for-unit-tests-only"

/**
 * What the test host answers on, and so the prefix on every avatar URL a test reads back.
 *
 * No port: the test engine serves `localhost:80` and `localhost:443`, so naming a port here would
 * publish addresses the test client refuses to resolve. Keeping it resolvable is what lets a test
 * take the `avatarUrl` out of a response and fetch it, exactly as an app would.
 */
const val TEST_PUBLIC_BASE_URL = "http://localhost"

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
        "app.publicBaseUrl" to TEST_PUBLIC_BASE_URL,
        // Cheap on purpose: the cost factor is a production concern, not a test one.
        "security.bcryptCost" to "4",
    ).toAppConfig()
}

/**
 * One pool and one Exposed registration for the whole test JVM.
 *
 * A test application used to run `configureDatabase`, which opens a pool and closes it again when
 * the application stops. Exposed resolves the database for a `dbQuery` through a thread local held
 * on the dispatcher thread, and those threads outlive the application that first used them, so a
 * request in a later test could land on a thread still pointing at a closed pool and fail with
 * "HikariDataSource has been closed". The server connects once in production, where that ambiguity
 * cannot arise; the harness now does the same.
 */
private val testDatabase: Database by lazy {
    val dataSource = createDataSource(testAppConfig().database)
    runMigrations(dataSource)
    Database.connect(dataSource)
}

/** Opens [testDatabase] on first use. Stands in for `configureDatabase`, which closes its pool. */
private fun connectTestDatabase(): Database = testDatabase

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
            connectTestDatabase()
            configureHttp()
            configureSecurity()
            configureSerialization()
            configureStatusPages()
            configureRequestValidation()
            configureDocs()
            authRoutes()
            userRoutes()
            imageRoutes()
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

/** Every test shares one database, so a provider account id has to be unique across the suite. */
fun uniqueProviderUserId(): String = "provider-user-${UUID.randomUUID()}"

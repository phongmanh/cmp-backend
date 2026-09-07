package com.example.plugins

import com.example.common.AppConfig
import com.example.common.JwtConfig
import com.example.common.toAppConfig
import com.example.feature.auth.AuthService
import com.example.feature.auth.PasswordHasher
import com.example.feature.auth.RefreshTokenRepository
import com.example.feature.auth.TokenService
import com.example.feature.auth.social.FacebookIdentityVerifier
import com.example.feature.auth.social.GoogleIdentityVerifier
import com.example.feature.auth.social.SocialIdentityVerifier
import com.example.feature.auth.social.SocialVerifierRegistry
import com.example.feature.image.ImageRepository
import com.example.feature.image.ImageService
import com.example.feature.user.UserRepository
import com.example.feature.user.UserService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun Application.configureKoin() {
    val appConfig = environment.config.toAppConfig()

    install(Koin) {
        slf4jLogger()
        modules(appModule(appConfig))
    }

    val httpClient by inject<HttpClient>()
    monitor.subscribe(ApplicationStopping) { httpClient.close() }
}

fun appModule(appConfig: AppConfig): Module =
    module {
        single { appConfig }
        single<JwtConfig> { appConfig.jwt }

        single {
            HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }
        }

        single { UserRepository() }
        single { ImageRepository() }
        single { RefreshTokenRepository() }
        single { TokenService(appConfig.jwt) }
        single { PasswordHasher(appConfig.bcryptCost) }

        single {
            val verifiers = mutableListOf<SocialIdentityVerifier>()
            appConfig.social.google?.let { verifiers += GoogleIdentityVerifier(it) }
            appConfig.social.facebook?.let { verifiers += FacebookIdentityVerifier(it, get()) }
            SocialVerifierRegistry(verifiers)
        }

        single { AuthService(get(), get(), get(), get(), get()) }
        single { UserService(get()) }
        single { ImageService(get(), get()) }
    }

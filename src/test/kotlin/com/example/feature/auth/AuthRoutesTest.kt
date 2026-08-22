package com.example.feature.auth

import com.example.api.ApiRoutes
import com.example.api.auth.LoginRequest
import com.example.api.auth.RefreshTokenRequest
import com.example.api.auth.RegisterRequest
import com.example.api.auth.SocialProvider
import com.example.api.auth.SocialSignInRequest
import com.example.api.auth.TokenResponse
import com.example.api.common.ErrorResponse
import com.example.api.user.UserResponse
import com.example.support.authTestApplication
import com.example.support.jsonClient
import com.example.support.uniqueEmail
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val VALID_PASSWORD = "a-long-enough-password"

class AuthRoutesTest {
    @Test
    fun `registers an account and returns a token pair`() =
        authTestApplication {
            val client = jsonClient()
            val email = uniqueEmail()

            val response = client.register(email, VALID_PASSWORD, "Ada")

            assertEquals(HttpStatusCode.Created, response.status)
            val body = response.body<TokenResponse>()
            assertEquals(email, body.user.email)
            assertEquals("Bearer", body.tokenType)
            assertTrue(body.accessToken.isNotBlank())
            assertTrue(body.refreshToken.isNotBlank())
            assertEquals(ApiRoutes.Users.byId(body.user.id), response.headers[HttpHeaders.Location])
        }

    @Test
    fun `rejects a registration whose password is too short`() =
        authTestApplication {
            val response = jsonClient().register(uniqueEmail(), "short")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("VALIDATION_ERROR", response.body<ErrorResponse>().code)
        }

    @Test
    fun `rejects a registration whose email is not an address`() =
        authTestApplication {
            val response = jsonClient().register("not-an-address", VALID_PASSWORD)

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("VALIDATION_ERROR", response.body<ErrorResponse>().code)
        }

    @Test
    fun `returns 409 when the email is already registered`() =
        authTestApplication {
            val client = jsonClient()
            val email = uniqueEmail()
            client.register(email, VALID_PASSWORD)

            val response = client.register(email, VALID_PASSWORD)

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("CONFLICT", response.body<ErrorResponse>().code)
        }

    @Test
    fun `logs in with the registered password`() =
        authTestApplication {
            val client = jsonClient()
            val email = uniqueEmail()
            client.register(email, VALID_PASSWORD)

            val response = client.login(email, VALID_PASSWORD)

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(email, response.body<TokenResponse>().user.email)
        }

    @Test
    fun `an unknown email and a wrong password are answered identically`() =
        authTestApplication {
            val client = jsonClient()
            val email = uniqueEmail()
            client.register(email, VALID_PASSWORD)

            val wrongPassword = client.login(email, "some-other-password")
            val unknownEmail = client.login(uniqueEmail(), VALID_PASSWORD)

            assertEquals(HttpStatusCode.Unauthorized, wrongPassword.status)
            assertEquals(HttpStatusCode.Unauthorized, unknownEmail.status)
            assertEquals(unknownEmail.body<ErrorResponse>(), wrongPassword.body<ErrorResponse>())
        }

    @Test
    fun `returns 401 for the profile when no token is sent`() =
        authTestApplication {
            val response = jsonClient().get(ApiRoutes.Users.ME)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `returns 401 for the profile when the token is not ours`() =
        authTestApplication {
            val response =
                jsonClient().get(ApiRoutes.Users.ME) {
                    bearerAuth("not.a.real.token")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `returns the caller profile without any password material`() =
        authTestApplication {
            val client = jsonClient()
            val email = uniqueEmail()
            val tokens = client.register(email, VALID_PASSWORD, "Ada").body<TokenResponse>()

            val response = client.get(ApiRoutes.Users.ME) { bearerAuth(tokens.accessToken) }

            assertEquals(HttpStatusCode.OK, response.status)
            val profile = response.body<UserResponse>()
            assertEquals(email, profile.email)
            assertEquals("Ada", profile.displayName)
            assertTrue(profile.linkedProviders.isEmpty())
        }

    @Test
    fun `refresh rotates the token and retires the previous one`() =
        authTestApplication {
            val client = jsonClient()
            val first = client.register(uniqueEmail(), VALID_PASSWORD).body<TokenResponse>()

            val second = client.refresh(first.refreshToken)

            assertEquals(HttpStatusCode.OK, second.status)
            val rotated = second.body<TokenResponse>()
            assertNotEquals(first.refreshToken, rotated.refreshToken)
            assertEquals(first.user.id, rotated.user.id)
        }

    @Test
    fun `replaying a retired refresh token revokes the whole family`() =
        authTestApplication {
            val client = jsonClient()
            val first = client.register(uniqueEmail(), VALID_PASSWORD).body<TokenResponse>()
            val rotated = client.refresh(first.refreshToken).body<TokenResponse>()

            val replay = client.refresh(first.refreshToken)
            val afterReplay = client.refresh(rotated.refreshToken)

            assertEquals(HttpStatusCode.Unauthorized, replay.status)
            assertEquals(HttpStatusCode.Unauthorized, afterReplay.status)
        }

    @Test
    fun `rejects a refresh token that was never issued`() =
        authTestApplication {
            val response = jsonClient().refresh("a-token-we-never-minted")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("UNAUTHENTICATED", response.body<ErrorResponse>().code)
        }

    @Test
    fun `logout stops the presented refresh token from being used again`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD).body<TokenResponse>()

            val logout =
                client.post(ApiRoutes.Auth.LOGOUT) {
                    bearerAuth(tokens.accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(RefreshTokenRequest(tokens.refreshToken))
                }
            val afterLogout = client.refresh(tokens.refreshToken)

            assertEquals(HttpStatusCode.NoContent, logout.status)
            assertEquals(HttpStatusCode.Unauthorized, afterLogout.status)
        }

    @Test
    fun `returns 401 when logging out without a token`() =
        authTestApplication {
            val response =
                jsonClient().post(ApiRoutes.Auth.LOGOUT) {
                    contentType(ContentType.Application.Json)
                    setBody(RefreshTokenRequest("anything"))
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
}

internal suspend fun HttpClient.register(
    email: String,
    password: String,
    displayName: String? = null,
    avatarUrl: String? = null,
): HttpResponse =
    post(ApiRoutes.Auth.REGISTER) {
        contentType(ContentType.Application.Json)
        setBody(RegisterRequest(email, password, displayName, avatarUrl))
    }

internal suspend fun HttpClient.login(
    email: String,
    password: String,
): HttpResponse =
    post(ApiRoutes.Auth.LOGIN) {
        contentType(ContentType.Application.Json)
        setBody(LoginRequest(email, password))
    }

internal suspend fun HttpClient.refresh(refreshToken: String): HttpResponse =
    post(ApiRoutes.Auth.REFRESH) {
        contentType(ContentType.Application.Json)
        setBody(RefreshTokenRequest(refreshToken))
    }

internal suspend fun HttpClient.socialSignIn(
    provider: SocialProvider,
    token: String,
): HttpResponse =
    post(ApiRoutes.Auth.SOCIAL) {
        contentType(ContentType.Application.Json)
        setBody(SocialSignInRequest(provider, token))
    }

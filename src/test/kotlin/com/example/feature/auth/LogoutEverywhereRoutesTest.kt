package com.example.feature.auth

import com.example.api.ApiRoutes
import com.example.api.auth.RefreshTokenRequest
import com.example.api.auth.TokenResponse
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals

private const val VALID_PASSWORD = "a-long-enough-password"

/**
 * `POST /api/v1/auth/logout-all` was the one endpoint the contract publishes that no behavioural
 * test exercised: it appeared only in the documentation test and in the shared path-string contract,
 * both of which pass whether or not the route revokes anything.
 */
class LogoutEverywhereRoutesTest {
    @Test
    fun `revokes every refresh token the caller owns`() =
        authTestApplication {
            val client = jsonClient()
            val email = uniqueEmail()
            val registration = client.register(email, VALID_PASSWORD).body<TokenResponse>()
            val secondDevice = client.login(email, VALID_PASSWORD).body<TokenResponse>()
            val thirdDevice = client.login(email, VALID_PASSWORD).body<TokenResponse>()

            val response = client.logoutEverywhere(registration.accessToken)

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals(HttpStatusCode.Unauthorized, client.refresh(registration.refreshToken).status)
            assertEquals(HttpStatusCode.Unauthorized, client.refresh(secondDevice.refreshToken).status)
            assertEquals(HttpStatusCode.Unauthorized, client.refresh(thirdDevice.refreshToken).status)
        }

    /** The endpoint revokes refresh tokens only, which is what makes the call cheap enough to be unrated. */
    @Test
    fun `leaves an access token already issued working until it expires`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD).body<TokenResponse>()

            client.logoutEverywhere(tokens.accessToken)

            val profile = client.get(ApiRoutes.Users.ME) { bearerAuth(tokens.accessToken) }
            assertEquals(HttpStatusCode.OK, profile.status)
        }

    @Test
    fun `signing out everywhere twice is not an error`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD).body<TokenResponse>()

            assertEquals(HttpStatusCode.NoContent, client.logoutEverywhere(tokens.accessToken).status)
            assertEquals(HttpStatusCode.NoContent, client.logoutEverywhere(tokens.accessToken).status)
        }

    @Test
    fun `one account's sign-out never touches another`() =
        authTestApplication {
            val client = jsonClient()
            val mine = client.register(uniqueEmail(), VALID_PASSWORD).body<TokenResponse>()
            val theirs = client.register(uniqueEmail(), VALID_PASSWORD).body<TokenResponse>()

            client.logoutEverywhere(mine.accessToken)

            assertEquals(HttpStatusCode.Unauthorized, client.refresh(mine.refreshToken).status)
            assertEquals(HttpStatusCode.OK, client.refresh(theirs.refreshToken).status)
        }

    /**
     * The route reads the caller from the principal and never calls `receive`, so a body is not
     * input it can be fed a bad value through.
     */
    @Test
    fun `ignores a body it never asked for`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD).body<TokenResponse>()

            val response =
                client.post(ApiRoutes.Auth.LOGOUT_ALL) {
                    bearerAuth(tokens.accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(RefreshTokenRequest("a token belonging to nobody"))
                }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals(HttpStatusCode.Unauthorized, client.refresh(tokens.refreshToken).status)
        }

    @Test
    fun `returns 401 when signing out everywhere without a token`() =
        authTestApplication {
            val response = jsonClient().post(ApiRoutes.Auth.LOGOUT_ALL)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `returns 401 when the access token is not ours`() =
        authTestApplication {
            val response =
                jsonClient().post(ApiRoutes.Auth.LOGOUT_ALL) {
                    bearerAuth("not.a.real.token")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
}

/** Takes no body, so this sends none. */
private suspend fun HttpClient.logoutEverywhere(accessToken: String): HttpResponse =
    post(ApiRoutes.Auth.LOGOUT_ALL) {
        bearerAuth(accessToken)
    }

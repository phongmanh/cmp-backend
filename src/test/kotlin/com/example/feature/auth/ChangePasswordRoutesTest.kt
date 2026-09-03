package com.example.feature.auth

import com.example.api.ApiRoutes
import com.example.api.auth.ChangePasswordRequest
import com.example.api.auth.SocialProvider
import com.example.api.auth.TokenResponse
import com.example.api.common.ErrorResponse
import com.example.support.FakeSocialVerifier
import com.example.support.authTestApplication
import com.example.support.jsonClient
import com.example.support.socialIdentity
import com.example.support.uniqueEmail
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val VALID_PASSWORD = "a-long-enough-password"
private const val NEW_PASSWORD = "an-entirely-different-one"

class ChangePasswordRoutesTest {
    @Test
    fun `replaces the password and hands back a fresh token pair`() =
        authTestApplication {
            val client = jsonClient()
            val email = uniqueEmail()
            val tokens = client.register(email, VALID_PASSWORD).body<TokenResponse>()

            val response = client.changePassword(tokens.accessToken, VALID_PASSWORD, NEW_PASSWORD)

            assertEquals(HttpStatusCode.OK, response.status)
            val issued = response.body<TokenResponse>()
            assertEquals(tokens.user.id, issued.user.id)
            assertTrue(issued.accessToken.isNotBlank())
            assertNotEquals(tokens.refreshToken, issued.refreshToken)
        }

    @Test
    fun `the new password is the one that logs in afterwards`() =
        authTestApplication {
            val client = jsonClient()
            val email = uniqueEmail()
            val tokens = client.register(email, VALID_PASSWORD).body<TokenResponse>()

            client.changePassword(tokens.accessToken, VALID_PASSWORD, NEW_PASSWORD)

            assertEquals(HttpStatusCode.OK, client.login(email, NEW_PASSWORD).status)
            assertEquals(HttpStatusCode.Unauthorized, client.login(email, VALID_PASSWORD).status)
        }

    @Test
    fun `every session opened before the change is dropped`() =
        authTestApplication {
            val client = jsonClient()
            val email = uniqueEmail()
            client.register(email, VALID_PASSWORD)
            val otherDevice = client.login(email, VALID_PASSWORD).body<TokenResponse>()
            val thisDevice = client.login(email, VALID_PASSWORD).body<TokenResponse>()

            val response = client.changePassword(thisDevice.accessToken, VALID_PASSWORD, NEW_PASSWORD)

            assertEquals(HttpStatusCode.Unauthorized, client.refresh(otherDevice.refreshToken).status)
            assertEquals(HttpStatusCode.Unauthorized, client.refresh(thisDevice.refreshToken).status)
            // Only the pair this call returned survives, so the device that changed it stays signed in.
            assertEquals(HttpStatusCode.OK, client.refresh(response.body<TokenResponse>().refreshToken).status)
        }

    @Test
    fun `returns 401 when the current password is wrong`() =
        authTestApplication {
            val client = jsonClient()
            val email = uniqueEmail()
            val tokens = client.register(email, VALID_PASSWORD).body<TokenResponse>()

            val response = client.changePassword(tokens.accessToken, "not-the-current-password", NEW_PASSWORD)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("UNAUTHENTICATED", response.body<ErrorResponse>().code)
            // The password stood, so the original one still works.
            assertEquals(HttpStatusCode.OK, client.login(email, VALID_PASSWORD).status)
        }

    @Test
    fun `a wrong current password leaves the existing sessions alone`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD).body<TokenResponse>()

            client.changePassword(tokens.accessToken, "not-the-current-password", NEW_PASSWORD)

            assertEquals(HttpStatusCode.OK, client.refresh(tokens.refreshToken).status)
        }

    @Test
    fun `rejects a new password that is too short`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD).body<TokenResponse>()

            val response = client.changePassword(tokens.accessToken, VALID_PASSWORD, "short")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("VALIDATION_ERROR", response.body<ErrorResponse>().code)
        }

    @Test
    fun `rejects a new password identical to the current one`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD).body<TokenResponse>()

            val response = client.changePassword(tokens.accessToken, VALID_PASSWORD, VALID_PASSWORD)

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("VALIDATION_ERROR", response.body<ErrorResponse>().code)
        }

    @Test
    fun `returns 422 for an account that only signs in through a provider`() {
        val verifier =
            FakeSocialVerifier(
                SocialProvider.GOOGLE,
                mapOf("google-token" to socialIdentity(email = uniqueEmail())),
            )

        authTestApplication(listOf(verifier)) {
            val client = jsonClient()
            val tokens = client.socialSignIn(SocialProvider.GOOGLE, "google-token").body<TokenResponse>()

            val response = client.changePassword(tokens.accessToken, VALID_PASSWORD, NEW_PASSWORD)

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertEquals("PASSWORD_NOT_SET", response.body<ErrorResponse>().code)
        }
    }

    @Test
    fun `returns 401 when changing a password without a token`() =
        authTestApplication {
            val response =
                jsonClient().post(ApiRoutes.Auth.PASSWORD) {
                    contentType(ContentType.Application.Json)
                    setBody(ChangePasswordRequest(VALID_PASSWORD, NEW_PASSWORD))
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `one account's change never touches another`() =
        authTestApplication {
            val client = jsonClient()
            val mine = client.register(uniqueEmail(), VALID_PASSWORD).body<TokenResponse>()
            val theirEmail = uniqueEmail()
            val theirs = client.register(theirEmail, VALID_PASSWORD).body<TokenResponse>()

            client.changePassword(mine.accessToken, VALID_PASSWORD, NEW_PASSWORD)

            assertEquals(HttpStatusCode.OK, client.login(theirEmail, VALID_PASSWORD).status)
            assertEquals(HttpStatusCode.OK, client.refresh(theirs.refreshToken).status)
        }
}

private suspend fun HttpClient.changePassword(
    accessToken: String,
    currentPassword: String,
    newPassword: String,
): HttpResponse =
    post(ApiRoutes.Auth.PASSWORD) {
        bearerAuth(accessToken)
        contentType(ContentType.Application.Json)
        setBody(ChangePasswordRequest(currentPassword, newPassword))
    }

package com.example.feature.user

import com.example.api.ApiRoutes
import com.example.api.auth.TokenResponse
import com.example.api.common.ErrorResponse
import com.example.api.common.FieldLimits
import com.example.api.user.UpdateProfileRequest
import com.example.api.user.UserResponse
import com.example.feature.auth.register
import com.example.support.authTestApplication
import com.example.support.jsonClient
import com.example.support.uniqueEmail
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

private const val VALID_PASSWORD = "a-long-enough-password"
private const val AVATAR_URL = "https://cdn.example.com/avatars/ada.png"
private const val OTHER_AVATAR_URL = "https://cdn.example.com/avatars/grace.png"

class UserRoutesTest {
    @Test
    fun `returns the avatar the account was registered with`() =
        authTestApplication {
            val client = jsonClient()
            val tokens =
                client.register(uniqueEmail(), VALID_PASSWORD, "Ada", AVATAR_URL).body<TokenResponse>()

            val response = client.get(ApiRoutes.Users.ME) { bearerAuth(tokens.accessToken) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(AVATAR_URL, response.body<UserResponse>().avatarUrl)
        }

    @Test
    fun `replaces the display name and the avatar`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD, "Ada", AVATAR_URL).body<TokenResponse>()

            val response =
                client.updateProfile(tokens.accessToken, UpdateProfileRequest("Grace Hopper", OTHER_AVATAR_URL))

            assertEquals(HttpStatusCode.OK, response.status)
            val updated = response.body<UserResponse>()
            assertEquals("Grace Hopper", updated.displayName)
            assertEquals(OTHER_AVATAR_URL, updated.avatarUrl)
            assertEquals(tokens.user.id, updated.id)
        }

    @Test
    fun `the update is what a later read returns`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD, "Ada").body<TokenResponse>()

            client.updateProfile(tokens.accessToken, UpdateProfileRequest("Grace Hopper", AVATAR_URL))
            val reread = client.get(ApiRoutes.Users.ME) { bearerAuth(tokens.accessToken) }.body<UserResponse>()

            assertEquals("Grace Hopper", reread.displayName)
            assertEquals(AVATAR_URL, reread.avatarUrl)
        }

    @Test
    fun `a null avatar clears the stored one`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD, "Ada", AVATAR_URL).body<TokenResponse>()

            val response = client.updateProfile(tokens.accessToken, UpdateProfileRequest(displayName = "Ada"))

            assertEquals(HttpStatusCode.OK, response.status)
            assertNull(response.body<UserResponse>().avatarUrl)
        }

    @Test
    fun `an update never reaches another account`() =
        authTestApplication {
            val client = jsonClient()
            val mine = client.register(uniqueEmail(), VALID_PASSWORD, "Ada", AVATAR_URL).body<TokenResponse>()
            val theirs = client.register(uniqueEmail(), VALID_PASSWORD, "Grace", OTHER_AVATAR_URL).body<TokenResponse>()

            client.updateProfile(mine.accessToken, UpdateProfileRequest("Ada Lovelace", null))
            val untouched = client.get(ApiRoutes.Users.ME) { bearerAuth(theirs.accessToken) }.body<UserResponse>()

            assertNotEquals(mine.user.id, untouched.id)
            assertEquals("Grace", untouched.displayName)
            assertEquals(OTHER_AVATAR_URL, untouched.avatarUrl)
        }

    @Test
    fun `rejects an avatar url that is not https`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD).body<TokenResponse>()

            val response =
                client.updateProfile(
                    tokens.accessToken,
                    UpdateProfileRequest(avatarUrl = "javascript:alert(1)"),
                )

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("VALIDATION_ERROR", response.body<ErrorResponse>().code)
        }

    @Test
    fun `rejects an avatar url longer than the column holds`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD).body<TokenResponse>()
            val tooLong = "https://cdn.example.com/" + "a".repeat(FieldLimits.MAX_AVATAR_URL_LENGTH)

            val response = client.updateProfile(tokens.accessToken, UpdateProfileRequest(avatarUrl = tooLong))

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("VALIDATION_ERROR", response.body<ErrorResponse>().code)
        }

    @Test
    fun `rejects a registration whose avatar url is not https`() =
        authTestApplication {
            val response = jsonClient().register(uniqueEmail(), VALID_PASSWORD, "Ada", "http://cdn.example.com/a.png")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("VALIDATION_ERROR", response.body<ErrorResponse>().code)
        }

    @Test
    fun `returns 401 when updating without a token`() =
        authTestApplication {
            val response =
                jsonClient().put(ApiRoutes.Users.ME) {
                    contentType(ContentType.Application.Json)
                    setBody(UpdateProfileRequest("Whoever", AVATAR_URL))
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
}

private suspend fun HttpClient.updateProfile(
    accessToken: String,
    request: UpdateProfileRequest,
): HttpResponse =
    put(ApiRoutes.Users.ME) {
        bearerAuth(accessToken)
        contentType(ContentType.Application.Json)
        setBody(request)
    }

package com.example.plugins

import com.example.api.ApiRoutes
import com.example.api.auth.TokenResponse
import com.example.api.common.ErrorResponse
import com.example.feature.auth.register
import com.example.support.authTestApplication
import com.example.support.jsonClient
import com.example.support.uniqueEmail
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

private const val VALID_PASSWORD = "a-long-enough-password"

/**
 * A routing failure never throws, so it reaches the client through the engine's fallback rather than
 * through an `exception` handler, and used to arrive with no body at all. These cover both codes
 * that fallback can produce here, and guard the two ways the fix could go wrong: swallowing a
 * message an exception handler had already written, and the authentication challenge, which answers
 * for itself in `configureSecurity` and must keep doing so.
 */
class StatusPagesTest {
    @Test
    fun `an unmatched path answers with the documented error body`() =
        authTestApplication {
            val response = jsonClient().get("/api/v1/nope")

            assertEquals(HttpStatusCode.NotFound, response.status)
            val body = response.body<ErrorResponse>()
            assertEquals("NOT_FOUND", body.code)
            assertEquals("No endpoint matches that path.", body.message)
        }

    @Test
    fun `a method the route does not answer gets the documented error body`() =
        authTestApplication {
            // The path matches, the verb does not: routing fails on the method rather than the path.
            val response = jsonClient().post(ApiRoutes.Users.ME)

            assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
            assertEquals("METHOD_NOT_ALLOWED", response.body<ErrorResponse>().code)
        }

    @Test
    fun `a protected route without a token names the failure`() =
        authTestApplication {
            // Answered by the `challenge` block in configureSecurity, not by a status handler. Pinned
            // here so removing that block shows up as a bare 401 rather than passing quietly.
            val response = jsonClient().get(ApiRoutes.Users.ME)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("UNAUTHENTICATED", response.body<ErrorResponse>().code)
        }

    @Test
    fun `a thrown not found keeps its own message`() =
        authTestApplication {
            val client = jsonClient()
            val mine = client.register(uniqueEmail(), VALID_PASSWORD).body<TokenResponse>()
            val theirs = client.register(uniqueEmail(), VALID_PASSWORD).body<TokenResponse>()

            val response =
                client.get(ApiRoutes.Users.byId(theirs.user.id)) { bearerAuth(mine.accessToken) }

            assertEquals(HttpStatusCode.NotFound, response.status)
            val body = response.body<ErrorResponse>()
            assertEquals("NOT_FOUND", body.code)
            assertNotEquals(
                "No endpoint matches that path.",
                body.message,
                "the status handler overwrote a message an exception handler had already written",
            )
        }
}

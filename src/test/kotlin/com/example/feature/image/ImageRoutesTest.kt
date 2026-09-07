package com.example.feature.image

import com.example.api.ApiRoutes
import com.example.api.auth.TokenResponse
import com.example.api.common.ErrorResponse
import com.example.api.common.FieldLimits
import com.example.api.user.UserResponse
import com.example.feature.auth.register
import com.example.support.authTestApplication
import com.example.support.jsonClient
import com.example.support.pngBytes
import com.example.support.uniqueEmail
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.io.ByteArrayInputStream
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val VALID_PASSWORD = "a-long-enough-password"

class ImageRoutesTest {
    /**
     * The one route in this API that answers without a token, which was a deliberate decision and
     * not an oversight. If somebody later moves it inside `authenticate`, this fails and says so.
     */
    @Test
    fun `serves an avatar to a caller with no token at all`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD, "Ada").body<TokenResponse>()
            val avatarUrl = client.uploadAvatar(tokens.accessToken, pngBytes()).body<UserResponse>().avatarUrl

            val response = client.get(avatarUrl!!)

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("image/jpeg", response.headers[HttpHeaders.ContentType])
            assertTrue(response.bodyAsBytes().isNotEmpty())
        }

    @Test
    fun `serves it as a square jpeg whatever was uploaded`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD, "Ada").body<TokenResponse>()
            val avatarUrl =
                client
                    .uploadAvatar(tokens.accessToken, pngBytes(width = 1200, height = 300))
                    .body<UserResponse>()
                    .avatarUrl

            val served = ImageIO.read(ByteArrayInputStream(client.get(avatarUrl!!).bodyAsBytes()))

            assertEquals(FieldLimits.AVATAR_EDGE_PX, served.width)
            assertEquals(FieldLimits.AVATAR_EDGE_PX, served.height)
        }

    @Test
    fun `offers the response for caching for a year`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD, "Ada").body<TokenResponse>()
            val avatarUrl = client.uploadAvatar(tokens.accessToken, pngBytes()).body<UserResponse>().avatarUrl

            val response = client.get(avatarUrl!!)

            val cacheControl = response.headers[HttpHeaders.CacheControl]
            assertNotNull(response.headers[HttpHeaders.ETag], "a cache needs something to revalidate against")
            assertTrue(
                cacheControl?.contains("immutable") == true,
                "an address that never changes content should say so, got $cacheControl",
            )
        }

    @Test
    fun `a matching If-None-Match answers 304 with no body`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD, "Ada").body<TokenResponse>()
            val avatarUrl = client.uploadAvatar(tokens.accessToken, pngBytes()).body<UserResponse>().avatarUrl
            val etag = client.get(avatarUrl!!).headers[HttpHeaders.ETag]

            val response = client.get(avatarUrl) { header(HttpHeaders.IfNoneMatch, etag!!) }

            assertEquals(HttpStatusCode.NotModified, response.status)
            assertTrue(response.bodyAsBytes().isEmpty(), "a 304 carries no body")
        }

    @Test
    fun `a weak validator inside a list still matches`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD, "Ada").body<TokenResponse>()
            val avatarUrl = client.uploadAvatar(tokens.accessToken, pngBytes()).body<UserResponse>().avatarUrl
            val etag = client.get(avatarUrl!!).headers[HttpHeaders.ETag]

            // Both shapes a cache is entitled to send: a list, and a weakened validator.
            val response = client.get(avatarUrl) { header(HttpHeaders.IfNoneMatch, """"other", W/$etag""") }

            assertEquals(HttpStatusCode.NotModified, response.status)
        }

    @Test
    fun `an unknown id reads as missing`() =
        authTestApplication {
            val response = jsonClient().get(ApiRoutes.Images.byId(UUID.randomUUID().toString()))

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("NOT_FOUND", response.body<ErrorResponse>().code)
        }

    @Test
    fun `rejects an id that is not a uuid`() =
        authTestApplication {
            val response = jsonClient().get(ApiRoutes.Images.byId("not-a-uuid"))

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("VALIDATION_ERROR", response.body<ErrorResponse>().code)
        }
}

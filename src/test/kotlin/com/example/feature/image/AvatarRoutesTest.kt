package com.example.feature.image

import com.example.api.ApiRoutes
import com.example.api.auth.TokenResponse
import com.example.api.common.ErrorResponse
import com.example.api.common.FieldLimits
import com.example.api.user.UpdateProfileRequest
import com.example.api.user.UserResponse
import com.example.feature.auth.register
import com.example.feature.user.UPLOAD_REQUESTS_PER_WINDOW
import com.example.support.TEST_PUBLIC_BASE_URL
import com.example.support.authTestApplication
import com.example.support.gifBytes
import com.example.support.jsonClient
import com.example.support.pngBytes
import com.example.support.uniqueEmail
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val VALID_PASSWORD = "a-long-enough-password"
private const val PROVIDER_AVATAR_URL = "https://cdn.example.com/avatars/ada.png"
private val IMAGES_PREFIX = "$TEST_PUBLIC_BASE_URL${ApiRoutes.Images.PATH}/"

class AvatarRoutesTest {
    @Test
    fun `stores an uploaded avatar and publishes it as an image address`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD, "Ada").body<TokenResponse>()

            val response = client.uploadAvatar(tokens.accessToken, pngBytes())

            assertEquals(HttpStatusCode.OK, response.status)
            val avatarUrl = response.body<UserResponse>().avatarUrl
            assertTrue(
                avatarUrl != null && avatarUrl.startsWith(IMAGES_PREFIX),
                "expected an address this server serves, got $avatarUrl",
            )
        }

    @Test
    fun `the uploaded avatar is what a later read returns`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD, "Ada").body<TokenResponse>()

            val uploaded = client.uploadAvatar(tokens.accessToken, pngBytes()).body<UserResponse>().avatarUrl
            val reread = client.get(ApiRoutes.Users.ME) { bearerAuth(tokens.accessToken) }.body<UserResponse>()

            assertEquals(uploaded, reread.avatarUrl)
        }

    @Test
    fun `an upload replaces the provider url the account registered with`() =
        authTestApplication {
            val client = jsonClient()
            val tokens =
                client.register(uniqueEmail(), VALID_PASSWORD, "Ada", PROVIDER_AVATAR_URL).body<TokenResponse>()

            val updated = client.uploadAvatar(tokens.accessToken, pngBytes()).body<UserResponse>()

            assertNotEquals(PROVIDER_AVATAR_URL, updated.avatarUrl)
            assertTrue(updated.avatarUrl?.startsWith(IMAGES_PREFIX) == true)
        }

    @Test
    fun `a second upload retires the first and its address stops answering`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD, "Ada").body<TokenResponse>()

            val first = client.uploadAvatar(tokens.accessToken, pngBytes()).body<UserResponse>().avatarUrl
            val second =
                client
                    .uploadAvatar(tokens.accessToken, pngBytes(colour = java.awt.Color.BLUE))
                    .body<UserResponse>()
                    .avatarUrl

            assertNotEquals(first, second, "a replaced avatar must live at a new address")
            assertEquals(HttpStatusCode.OK, client.get(second!!).status)
            assertEquals(
                HttpStatusCode.NotFound,
                client.get(first!!).status,
                "the superseded image is deleted, so its address must stop answering",
            )
        }

    @Test
    fun `a profile update retires an uploaded avatar`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD, "Ada").body<TokenResponse>()
            val uploaded = client.uploadAvatar(tokens.accessToken, pngBytes()).body<UserResponse>().avatarUrl

            val updated =
                client
                    .put(ApiRoutes.Users.ME) {
                        bearerAuth(tokens.accessToken)
                        contentType(ContentType.Application.Json)
                        setBody(UpdateProfileRequest("Ada", PROVIDER_AVATAR_URL))
                    }.body<UserResponse>()

            assertEquals(PROVIDER_AVATAR_URL, updated.avatarUrl)
            assertEquals(
                HttpStatusCode.NotFound,
                client.get(uploaded!!).status,
                "nothing points at the uploaded image any more, so it must not be left behind",
            )
        }

    @Test
    fun `an upload never reaches another account`() =
        authTestApplication {
            val client = jsonClient()
            val mine = client.register(uniqueEmail(), VALID_PASSWORD, "Ada").body<TokenResponse>()
            val theirs =
                client.register(uniqueEmail(), VALID_PASSWORD, "Grace", PROVIDER_AVATAR_URL).body<TokenResponse>()

            client.uploadAvatar(mine.accessToken, pngBytes())
            val untouched = client.get(ApiRoutes.Users.ME) { bearerAuth(theirs.accessToken) }.body<UserResponse>()

            assertEquals(PROVIDER_AVATAR_URL, untouched.avatarUrl)
        }

    @Test
    fun `rejects a gif`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD).body<TokenResponse>()

            val response = client.uploadAvatar(tokens.accessToken, gifBytes())

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertEquals("UNSUPPORTED_IMAGE", response.body<ErrorResponse>().code)
        }

    @Test
    fun `rejects an svg dressed up as a png`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD).body<TokenResponse>()
            val svg = """<svg xmlns="http://www.w3.org/2000/svg" onload="alert(1)"/>""".toByteArray()

            // Declared image/png and named .png. Only the bytes decide, so both are ignored.
            val response = client.uploadAvatar(tokens.accessToken, svg)

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertEquals("UNSUPPORTED_IMAGE", response.body<ErrorResponse>().code)
        }

    @Test
    fun `rejects a body that is not multipart`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD).body<TokenResponse>()

            val response =
                client.post(ApiRoutes.Users.ME_AVATAR) {
                    bearerAuth(tokens.accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(UpdateProfileRequest("Ada", null))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("VALIDATION_ERROR", response.body<ErrorResponse>().code)
        }

    @Test
    fun `rejects a part that is not named file`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD).body<TokenResponse>()

            val response = client.uploadAvatar(tokens.accessToken, pngBytes(), partName = "picture")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("VALIDATION_ERROR", response.body<ErrorResponse>().code)
        }

    @Test
    fun `rejects an upload past the file cap`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD).body<TokenResponse>()
            // Fits inside the request cap, so the header check lets it through and the streaming
            // cap in the read loop is the one that has to catch it.
            val tooBig = ByteArray(FieldLimits.MAX_AVATAR_BYTES.toInt() + 1)

            val response = client.uploadAvatar(tokens.accessToken, tooBig)

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals("PAYLOAD_TOO_LARGE", response.body<ErrorResponse>().code)
        }

    @Test
    fun `rejects a request past the envelope cap before reading it`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD).body<TokenResponse>()
            val wayTooBig = ByteArray(FieldLimits.MAX_AVATAR_REQUEST_BYTES.toInt() + 1)

            val response = client.uploadAvatar(tokens.accessToken, wayTooBig)

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals("PAYLOAD_TOO_LARGE", response.body<ErrorResponse>().code)
        }

    @Test
    fun `returns 401 when uploading without a token`() =
        authTestApplication {
            val response =
                jsonClient().post(ApiRoutes.Users.ME_AVATAR) {
                    setBody(multipartAvatar(pngBytes(), FieldLimits.AVATAR_PART_NAME))
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `deleting the avatar leaves the account without one`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD, "Ada").body<TokenResponse>()
            val uploaded = client.uploadAvatar(tokens.accessToken, pngBytes()).body<UserResponse>().avatarUrl

            val response = client.delete(ApiRoutes.Users.ME_AVATAR) { bearerAuth(tokens.accessToken) }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertNull(client.get(ApiRoutes.Users.ME) { bearerAuth(tokens.accessToken) }.body<UserResponse>().avatarUrl)
            assertEquals(HttpStatusCode.NotFound, client.get(uploaded!!).status)
        }

    @Test
    fun `the upload limit does not stop the account removing its avatar`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD, "Ada").body<TokenResponse>()

            // Spend the whole upload allowance, then take the picture down. Sharing one bucket
            // between the two would leave somebody who had just changed their avatar a few times
            // unable to remove it, which is the wrong thing to ration.
            repeat(UPLOAD_REQUESTS_PER_WINDOW + 1) { client.uploadAvatar(tokens.accessToken, pngBytes()) }

            val response = client.delete(ApiRoutes.Users.ME_AVATAR) { bearerAuth(tokens.accessToken) }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertNull(client.get(ApiRoutes.Users.ME) { bearerAuth(tokens.accessToken) }.body<UserResponse>().avatarUrl)
        }

    @Test
    fun `refuses an upload once the account is over the limit`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD, "Ada").body<TokenResponse>()

            repeat(UPLOAD_REQUESTS_PER_WINDOW) { client.uploadAvatar(tokens.accessToken, pngBytes()) }
            val response = client.uploadAvatar(tokens.accessToken, pngBytes())

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
        }

    @Test
    fun `deleting an avatar the account does not have is still 204`() =
        authTestApplication {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD).body<TokenResponse>()

            val response = client.delete(ApiRoutes.Users.ME_AVATAR) { bearerAuth(tokens.accessToken) }

            assertEquals(HttpStatusCode.NoContent, response.status)
        }

    @Test
    fun `returns 401 when deleting without a token`() =
        authTestApplication {
            val response = jsonClient().delete(ApiRoutes.Users.ME_AVATAR)

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
}

internal suspend fun HttpClient.uploadAvatar(
    accessToken: String,
    bytes: ByteArray,
    partName: String = FieldLimits.AVATAR_PART_NAME,
    fileName: String = "avatar.png",
    declaredType: ContentType = ContentType.Image.PNG,
): HttpResponse =
    post(ApiRoutes.Users.ME_AVATAR) {
        bearerAuth(accessToken)
        setBody(multipartAvatar(bytes, partName, fileName, declaredType))
    }

private fun multipartAvatar(
    bytes: ByteArray,
    partName: String,
    fileName: String = "avatar.png",
    declaredType: ContentType = ContentType.Image.PNG,
) = MultiPartFormDataContent(
    formData {
        append(
            partName,
            bytes,
            Headers.build {
                append(HttpHeaders.ContentType, declaredType.toString())
                append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
            },
        )
    },
)

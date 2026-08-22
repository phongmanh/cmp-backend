package com.example.api

import com.example.api.auth.SocialProvider
import com.example.api.auth.SocialSignInRequest
import com.example.api.auth.TokenResponse
import com.example.api.common.FieldLimits
import com.example.api.user.UpdateProfileRequest
import com.example.api.user.UserResponse
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `route constants carry the version prefix`() {
        assertEquals("/api/v1/auth/register", ApiRoutes.Auth.REGISTER)
        assertEquals("/api/v1/auth/logout-all", ApiRoutes.Auth.LOGOUT_ALL)
        assertEquals("/api/v1/users/me", ApiRoutes.Users.ME)
    }

    @Test
    fun `byId fills the template the server registers`() {
        assertEquals("/api/v1/users/{userId}", ApiRoutes.Users.BY_ID)
        assertEquals("/api/v1/users/abc-123", ApiRoutes.Users.byId("abc-123"))
    }

    @Test
    fun `social provider travels as its lowercase key`() {
        val encoded = json.encodeToString(SocialSignInRequest(SocialProvider.GOOGLE, "id-token"))
        assertEquals("""{"provider":"google","token":"id-token"}""", encoded)
        assertEquals(SocialProvider.FACEBOOK, SocialProvider.fromKey("facebook"))
        assertNull(SocialProvider.fromKey("twitter"))
    }

    @Test
    fun `token response decodes with tokenType left out`() {
        val body =
            """
            {"accessToken":"a","refreshToken":"r","expiresIn":900,
             "user":{"id":"u","email":null,"displayName":null,"avatarUrl":null,"isEmailVerified":false,
                     "createdAt":"2026-01-01T00:00:00Z","linkedProviders":[]}}
            """.trimIndent()

        val decoded = json.decodeFromString<TokenResponse>(body)

        assertEquals("Bearer", decoded.tokenType)
        assertEquals(UserResponse("u", null, null, null, false, "2026-01-01T00:00:00Z", emptyList()), decoded.user)
    }

    @Test
    fun `user response carries the avatar url both ways`() {
        val user =
            UserResponse(
                id = "u",
                email = "user@example.com",
                displayName = "User",
                avatarUrl = "https://cdn.example.com/u.png",
                isEmailVerified = true,
                createdAt = "2026-01-01T00:00:00Z",
                linkedProviders = listOf("google"),
            )

        val encoded = json.encodeToString(user)

        assertContains(encoded, """"avatarUrl":"https://cdn.example.com/u.png"""")
        assertEquals(user, json.decodeFromString<UserResponse>(encoded))
    }

    @Test
    fun `an omitted profile field decodes as a cleared one`() {
        val decoded = json.decodeFromString<UpdateProfileRequest>("{}")

        assertNull(decoded.displayName)
        assertNull(decoded.avatarUrl)
    }

    @Test
    fun `the avatar rule accepts only an absolute https url`() {
        assertTrue(FieldLimits.AVATAR_URL_PATTERN.matches("https://cdn.example.com/a.png"))
        assertFalse(FieldLimits.AVATAR_URL_PATTERN.matches("http://cdn.example.com/a.png"))
        assertFalse(FieldLimits.AVATAR_URL_PATTERN.matches("javascript:alert(1)"))
        assertFalse(FieldLimits.AVATAR_URL_PATTERN.matches("data:image/png;base64,iVBORw0KGgo="))
        assertFalse(FieldLimits.AVATAR_URL_PATTERN.matches("//cdn.example.com/a.png"))
    }
}

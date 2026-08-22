package com.example.feature.auth

import com.example.api.auth.SocialProvider
import com.example.api.auth.SocialSignInRequest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SocialSignInRequestTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `reads a supported provider`() {
        val request = json.decodeFromString<SocialSignInRequest>("""{"provider":"google","token":"abc"}""")

        assertEquals(SocialProvider.GOOGLE, request.provider)
        assertEquals("abc", request.token)
    }

    @Test
    fun `rejects a provider we do not support instead of defaulting it`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<SocialSignInRequest>("""{"provider":"twitter","token":"abc"}""")
        }
    }

    @Test
    fun `provider key matches the value stored against an identity`() {
        assertEquals("google", SocialProvider.GOOGLE.key)
        assertEquals("facebook", SocialProvider.FACEBOOK.key)
    }
}

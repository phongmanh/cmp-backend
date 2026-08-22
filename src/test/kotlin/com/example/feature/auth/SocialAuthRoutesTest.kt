package com.example.feature.auth

import com.example.api.ApiRoutes
import com.example.api.auth.SocialProvider
import com.example.api.auth.SocialSignInRequest
import com.example.api.auth.TokenResponse
import com.example.api.common.ErrorResponse
import com.example.api.user.UserResponse
import com.example.support.FakeSocialVerifier
import com.example.support.authTestApplication
import com.example.support.jsonClient
import com.example.support.socialIdentity
import com.example.support.uniqueEmail
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val VALID_PASSWORD = "a-long-enough-password"

class SocialAuthRoutesTest {
    @Test
    fun `creates an account on the first google sign-in`() {
        val email = uniqueEmail()
        val verifier =
            FakeSocialVerifier(
                SocialProvider.GOOGLE,
                mapOf("google-token" to socialIdentity(providerUserId = "google-1", email = email, displayName = "Ada")),
            )

        authTestApplication(listOf(verifier)) {
            val response = jsonClient().socialSignIn(SocialProvider.GOOGLE, "google-token")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.body<TokenResponse>()
            assertEquals(email, body.user.email)
            assertEquals("Ada", body.user.displayName)
            assertTrue(body.user.isEmailVerified)
        }
    }

    @Test
    fun `keeps the picture the provider handed over`() {
        val verifier =
            FakeSocialVerifier(
                SocialProvider.GOOGLE,
                mapOf(
                    "google-token" to
                        socialIdentity(
                            providerUserId = "google-avatar",
                            email = uniqueEmail(),
                            avatarUrl = "https://lh3.googleusercontent.com/a/ada",
                        ),
                ),
            )

        authTestApplication(listOf(verifier)) {
            val response = jsonClient().socialSignIn(SocialProvider.GOOGLE, "google-token")

            assertEquals("https://lh3.googleusercontent.com/a/ada", response.body<TokenResponse>().user.avatarUrl)
        }
    }

    @Test
    fun `drops a provider picture we would refuse from a client`() {
        val verifier =
            FakeSocialVerifier(
                SocialProvider.GOOGLE,
                mapOf(
                    "google-token" to
                        socialIdentity(
                            providerUserId = "google-bad-avatar",
                            email = uniqueEmail(),
                            avatarUrl = "http://lh3.googleusercontent.com/a/ada",
                        ),
                ),
            )

        authTestApplication(listOf(verifier)) {
            val response = jsonClient().socialSignIn(SocialProvider.GOOGLE, "google-token")

            assertEquals(HttpStatusCode.OK, response.status)
            assertNull(response.body<TokenResponse>().user.avatarUrl)
        }
    }

    @Test
    fun `signs in to the same account the second time`() {
        val verifier =
            FakeSocialVerifier(
                SocialProvider.GOOGLE,
                mapOf("google-token" to socialIdentity(providerUserId = "google-2", email = uniqueEmail())),
            )

        authTestApplication(listOf(verifier)) {
            val client = jsonClient()

            val first = client.socialSignIn(SocialProvider.GOOGLE, "google-token").body<TokenResponse>()
            val second = client.socialSignIn(SocialProvider.GOOGLE, "google-token").body<TokenResponse>()

            assertEquals(first.user.id, second.user.id)
        }
    }

    @Test
    fun `a verified provider email takes over an account whose password was never verified`() {
        val email = uniqueEmail()
        val verifier =
            FakeSocialVerifier(
                SocialProvider.GOOGLE,
                mapOf("google-token" to socialIdentity(providerUserId = "google-3", email = email)),
            )

        authTestApplication(listOf(verifier)) {
            val client = jsonClient()
            val registered = client.register(email, VALID_PASSWORD).body<TokenResponse>()

            val social = client.socialSignIn(SocialProvider.GOOGLE, "google-token")
            val passwordLogin = client.login(email, VALID_PASSWORD)

            assertEquals(HttpStatusCode.OK, social.status)
            assertEquals(registered.user.id, social.body<TokenResponse>().user.id)
            // Whoever set that password never proved they owned the mailbox, so it is gone.
            assertEquals(HttpStatusCode.Unauthorized, passwordLogin.status)
        }
    }

    @Test
    fun `an unverified provider email never reaches an existing account`() {
        val email = uniqueEmail()
        val verifier =
            FakeSocialVerifier(
                SocialProvider.GOOGLE,
                mapOf(
                    "google-token" to
                        socialIdentity(providerUserId = "google-4", email = email, isEmailVerified = false),
                ),
            )

        authTestApplication(listOf(verifier)) {
            val client = jsonClient()
            val registered = client.register(email, VALID_PASSWORD).body<TokenResponse>()

            val social = client.socialSignIn(SocialProvider.GOOGLE, "google-token").body<TokenResponse>()

            assertTrue(registered.user.id != social.user.id)
            // The address was not proven, so the new account does not get to claim it.
            assertNull(social.user.email)
            assertEquals(HttpStatusCode.OK, client.login(email, VALID_PASSWORD).status)
        }
    }

    @Test
    fun `rejects a token the provider does not recognise`() {
        val verifier = FakeSocialVerifier(SocialProvider.GOOGLE, emptyMap())

        authTestApplication(listOf(verifier)) {
            val response = jsonClient().socialSignIn(SocialProvider.GOOGLE, "a-token-google-never-issued")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("UNAUTHENTICATED", response.body<ErrorResponse>().code)
        }
    }

    @Test
    fun `rejects a provider that is not configured`() {
        val verifier = FakeSocialVerifier(SocialProvider.GOOGLE, emptyMap())

        authTestApplication(listOf(verifier)) {
            val response = jsonClient().socialSignIn(SocialProvider.FACEBOOK, "facebook-token")

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertEquals("PROVIDER_NOT_ENABLED", response.body<ErrorResponse>().code)
        }
    }

    @Test
    fun `rejects a provider name we do not support`() =
        authTestApplication {
            val response =
                jsonClient().post(ApiRoutes.Auth.SOCIAL) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"provider":"twitter","token":"anything"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `returns 401 when linking a provider without an access token`() =
        authTestApplication {
            val response =
                jsonClient().post(ApiRoutes.Auth.LINK) {
                    contentType(ContentType.Application.Json)
                    setBody(SocialSignInRequest(SocialProvider.GOOGLE, "google-token"))
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `links a provider to the signed-in account`() {
        val verifier =
            FakeSocialVerifier(
                SocialProvider.GOOGLE,
                mapOf("google-token" to socialIdentity(providerUserId = "google-5", email = uniqueEmail())),
            )

        authTestApplication(listOf(verifier)) {
            val client = jsonClient()
            val tokens = client.register(uniqueEmail(), VALID_PASSWORD).body<TokenResponse>()

            val response =
                client.post(ApiRoutes.Auth.LINK) {
                    bearerAuth(tokens.accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(SocialSignInRequest(SocialProvider.GOOGLE, "google-token"))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(listOf("google"), response.body<UserResponse>().linkedProviders)
        }
    }

    @Test
    fun `refuses to link a provider account that belongs to somebody else`() {
        val verifier =
            FakeSocialVerifier(
                SocialProvider.GOOGLE,
                mapOf("google-token" to socialIdentity(providerUserId = "google-6", email = uniqueEmail())),
            )

        authTestApplication(listOf(verifier)) {
            val client = jsonClient()
            val owner = client.register(uniqueEmail(), VALID_PASSWORD).body<TokenResponse>()
            client.post(ApiRoutes.Auth.LINK) {
                bearerAuth(owner.accessToken)
                contentType(ContentType.Application.Json)
                setBody(SocialSignInRequest(SocialProvider.GOOGLE, "google-token"))
            }
            val other = client.register(uniqueEmail(), VALID_PASSWORD).body<TokenResponse>()

            val response =
                client.post(ApiRoutes.Auth.LINK) {
                    bearerAuth(other.accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(SocialSignInRequest(SocialProvider.GOOGLE, "google-token"))
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
        }
    }
}

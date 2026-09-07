package com.example.plugins

import com.example.support.authTestApplication
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The specification is generated, so nothing here re-states what the routing tree already says.
 * These guard the two things generation cannot check: that a new route was actually described, and
 * that it landed on the side of the authentication boundary its author meant.
 */
class DocsRoutesTest {
    @Test
    fun `serves the swagger page without a token`() =
        authTestApplication {
            val response = client.get("/docs")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("swagger", ignoreCase = true), "expected the Swagger UI page")
        }

    @Test
    fun `serves a generated specification without a token`() =
        authTestApplication {
            val response = client.get("/openapi.json")

            assertEquals(HttpStatusCode.OK, response.status)
            val document = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertTrue(
                document
                    .getValue("openapi")
                    .jsonPrimitive.content
                    .startsWith("3."),
                "expected an OpenAPI 3 document",
            )
            assertTrue(document.paths().isNotEmpty(), "the specification documents no routes at all")
        }

    @Test
    fun `advertises the local development server`() =
        authTestApplication {
            val servers =
                Json
                    .parseToJsonElement(client.get("/openapi.json").bodyAsText())
                    .jsonObject
                    .getValue("servers")
                    .jsonArray
                    .map {
                        it.jsonObject
                            .getValue("url")
                            .jsonPrimitive.content
                    }

            assertEquals(listOf("http://localhost:8080"), servers)
        }

    @Test
    fun `describes every route it publishes`() =
        authTestApplication {
            val undescribed =
                operations().filter { (_, operation) ->
                    operation["summary"]?.jsonPrimitive?.content.isNullOrBlank() || operation["tags"] == null
                }

            assertTrue(
                undescribed.isEmpty(),
                "these routes reach the specification without a describe block: ${undescribed.map { it.first }}",
            )
        }

    @Test
    fun `marks exactly the protected routes as requiring a token`() =
        authTestApplication {
            val secured =
                operations()
                    .filter { (_, operation) -> operation.containsKey("security") }
                    .map { (name, _) -> name }
                    .toSet()

            assertEquals(
                setOf(
                    "post /api/v1/auth/password",
                    "post /api/v1/auth/logout",
                    "post /api/v1/auth/logout-all",
                    "post /api/v1/auth/link",
                    "get /api/v1/users/me",
                    "put /api/v1/users/me",
                    "post /api/v1/users/me/avatar",
                    "delete /api/v1/users/me/avatar",
                    "get /api/v1/users/{userId}",
                    // `get /api/v1/images/{imageId}` is deliberately absent. Serving an avatar
                    // needs no token: the id is a random UUID and standing in for the credential
                    // is its whole job, which is what lets an ordinary image loader fetch one.
                    // If it ever appears here, somebody has made it private and should mean to.
                ),
                secured,
            )
        }

    @Test
    fun `shows a worked example for every request body`() =
        authTestApplication {
            val bare =
                operations()
                    .filter { (_, operation) -> operation.containsKey("requestBody") }
                    .filter { (_, operation) ->
                        // An upload declares `multipart/form-data` and nothing else. There is no
                        // worked example to show for a file picker, so only JSON bodies are held to
                        // this; `documents every request body it publishes` covers the rest.
                        val json =
                            operation
                                .getValue("requestBody")
                                .jsonObject
                                .getValue("content")
                                .jsonObject["application/json"]
                                ?.jsonObject
                                ?: return@filter false
                        json["examples"]?.jsonObject.isNullOrEmpty() || json["schema"] == null
                    }.map { (name, _) -> name }

            assertTrue(bare.isEmpty(), "these request bodies have no example or no schema: $bare")
        }

    @Test
    fun `documents every request body it publishes`() =
        authTestApplication {
            val contentless =
                operations()
                    .filter { (_, operation) -> operation.containsKey("requestBody") }
                    .filter { (_, operation) ->
                        operation
                            .getValue("requestBody")
                            .jsonObject["content"]
                            ?.jsonObject
                            .isNullOrEmpty()
                    }.map { (name, _) -> name }

            assertTrue(contentless.isEmpty(), "these request bodies declare no content type at all: $contentless")
        }

    @Test
    fun `publishes the image endpoint as bytes rather than as json`() =
        authTestApplication {
            val image = operations().single { (name, _) -> name == "get /api/v1/images/{imageId}" }.second

            val contentTypes =
                image
                    .getValue("responses")
                    .jsonObject
                    .getValue("200")
                    .jsonObject
                    .getValue("content")
                    .jsonObject
                    .keys

            assertTrue("image/jpeg" in contentTypes, "an avatar is served as an image, got $contentTypes")
        }

    @Test
    fun `keeps the template scaffolding out of the specification`() =
        authTestApplication {
            val paths =
                Json
                    .parseToJsonElement(client.get("/openapi.json").bodyAsText())
                    .jsonObject
                    .paths()
                    .keys

            assertTrue(paths.none { it == "/" || it.startsWith("/json/") || it.startsWith("/openapi") })
        }

    private fun JsonObject.paths(): Map<String, JsonObject> = getValue("paths").jsonObject.mapValues { (_, item) -> item.jsonObject }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.operations(): List<Pair<String, JsonObject>> =
        Json
            .parseToJsonElement(client.get("/openapi.json").bodyAsText())
            .jsonObject
            .paths()
            .flatMap { (path, item) -> item.map { (method, operation) -> "$method $path" to operation.jsonObject } }
}

package com.example.api

import com.example.support.authTestApplication
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A path in [ApiRoutes] is a `const val`, and a constant cannot register anything. `Users.BY_ID` was
 * declared, documented and sent out in a `Location` header for a route that did not exist, which
 * only showed up as a `404` in production. This closes that gap: a path the contract publishes has
 * to appear in the generated specification, or the build fails.
 *
 * The specification is the source of truth here rather than the routing tree, so a route that is
 * registered and then `hide()`n would read as missing. Nothing under [ApiRoutes] is hidden — only
 * the template scaffolding in `Routing.kt` and `/openapi.json` itself are.
 */
class ApiRoutesCoverageTest {
    @Test
    fun `every path the contract declares is registered on the server`() =
        authTestApplication {
            val registered =
                Json
                    .parseToJsonElement(client.get("/openapi.json").bodyAsText())
                    .jsonObject
                    .getValue("paths")
                    .jsonObject
                    .keys

            val missing = declaredEndpoints().filterNot { (_, path) -> path in registered }

            assertTrue(
                missing.isEmpty(),
                "declared in ApiRoutes but registered nowhere: " +
                    missing.joinToString { (name, path) -> "$name ($path)" },
            )
        }

    /** Without this, a rename could quietly reduce the guard above to asserting nothing. */
    @Test
    fun `the guard reads the endpoints and skips the namespaces`() {
        val declared = declaredEndpoints()
        val paths = declared.map { (_, path) -> path }
        val names = declared.map { (name, _) -> name }

        assertTrue(ApiRoutes.Auth.REGISTER in paths, "expected the register endpoint")
        assertTrue(ApiRoutes.Users.ME in paths, "expected the current user endpoint")
        assertTrue(ApiRoutes.Users.BY_ID in paths, "expected the user by id template")
        assertTrue("PATH" !in names, "a group prefix is a namespace, not an endpoint")
        assertTrue(ApiRoutes.Users.USER_ID !in paths, "a parameter name is not a path")
    }
}

/**
 * Every `String` constant on a route group whose value starts with the version prefix, paired with
 * its name so a failure can say which one. A constant named `PATH` is the group's own prefix rather
 * than something a client calls, and `USER_ID` holds a parameter name, which the prefix test drops.
 *
 * Plain Java reflection on purpose: `const val` compiles to a static field, so this needs no
 * `kotlin-reflect` on the test classpath.
 */
private fun declaredEndpoints(): List<Pair<String, String>> =
    listOf(ApiRoutes.Auth, ApiRoutes.Users).flatMap { group ->
        group::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .filter { it.type == String::class.java && it.name != "PATH" }
            .map { it.name to it.get(group) as String }
            .filter { (_, path) -> path.startsWith(ApiRoutes.API_PREFIX) }
    }

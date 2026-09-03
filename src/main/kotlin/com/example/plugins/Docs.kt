package com.example.plugins

import io.ktor.http.ContentType
import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import io.ktor.openapi.Server
import io.ktor.server.application.Application
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.routing
import io.ktor.utils.io.ExperimentalKtorApi

/**
 * Interactive documentation, in the style of FastAPI's `/docs`.
 *
 * The specification is generated from the routing tree rather than kept in a file beside it: paths,
 * methods, request and response schemas and the security requirements all come from the code the
 * server actually runs, so they cannot drift. What the code cannot know — prose, and the failures
 * `StatusPages` produces — is declared per route with `describe { }`.
 */
@OptIn(ExperimentalKtorApi::class)
fun Application.configureDocs() {
    routing {
        swaggerUI(path = "docs") {
            info = apiInfo()
            servers { apiServers().forEach { server(it.url) { description = it.description } } }
            source = OpenApiDocSource.Routing(contentType = ContentType.Application.Json)
            remotePath = "openapi.json"
            // Anchors the browser URL to the operation being read, so a link to one endpoint works.
            deepLinking = true
        }

        // The same document Swagger UI reads, at a stable address for client generators.
        get("/openapi.json") {
            val document = jsonSource.read(call.application, OpenApiDoc(info = apiInfo(), servers = apiServers()))
            call.respondText(document.content, document.contentType)
        }.hide()
    }
}

private val jsonSource = OpenApiDocSource.Routing(contentType = ContentType.Application.Json)

// Swagger UI calls the machine the server runs on. A deployed instance is reached at its own host,
// which this document does not need to name.
private fun apiServers(): List<Server> = listOf(Server(url = "http://localhost:8080", description = "Local development"))

private fun apiInfo(): OpenApiInfo =
    OpenApiInfo(
        title = "ktor-sample API",
        version = "1.0.0",
        description = API_DESCRIPTION,
    )

private val API_DESCRIPTION =
    """
    Authentication and user API for the mobile app.

    ## Authenticating

    Register or sign in, then send the returned `accessToken` on every protected call:

    ```
    Authorization: Bearer <accessToken>
    ```

    An access token lives 15 minutes by default (`JWT_ACCESS_TOKEN_MINUTES`). When it expires,
    exchange the `refreshToken` at `POST /api/v1/auth/refresh` for a fresh pair. Refresh tokens
    rotate: the old one is retired the moment a new one is issued. Presenting a retired refresh
    token is treated as a leak and revokes every token in that login's family, so the user must
    sign in again.

    Changing a password at `POST /api/v1/auth/password` revokes **every** refresh token the account
    holds, the one you sent it with included. That response carries a replacement pair, so store
    both and carry on; every other device has to sign in again with the new password.

    ## Conventions

    - Every field name in JSON is `camelCase`.
    - Every timestamp is an ISO-8601 string in UTC, e.g. `2026-08-19T09:41:12.804Z`.
    - Every error, at every status code except `429`, uses the same `ErrorResponse` body.
    - Request bodies are capped at 64 KB; a larger one is rejected with `413` before it is read.
    - `linkedProviders` is always empty inside a token response. Call `GET /api/v1/users/me` when
      you need the providers linked to an account.

    ## Error codes

    The status code tells you what kind of failure it is; the `code` field is the stable identifier
    to branch on. `message` is written for a person and may be reworded between releases.

    | `code` | Status | Meaning |
    |---|---|---|
    | `VALIDATION_ERROR` | 400 | A field failed a length, format or presence rule. |
    | `INVALID_REQUEST` | 400 | The body is missing, malformed, or carries an unknown enum value. |
    | `UNAUTHENTICATED` | 401 | No token, a bad token, bad credentials, or a dead refresh token. |
    | `FORBIDDEN` | 403 | The token is valid but the caller may not touch this resource. |
    | `NOT_FOUND` | 404 | The resource does not exist. |
    | `METHOD_NOT_ALLOWED` | 405 | The path exists but does not answer that verb. |
    | `CONFLICT` | 409 | The email is taken, or the social account belongs to somebody else. |
    | `PAYLOAD_TOO_LARGE` | 413 | The body is over 64 KB. |
    | `PROVIDER_NOT_ENABLED` | 422 | That social provider is not configured on this deployment. |
    | `PASSWORD_NOT_SET` | 422 | The account signs in through a provider and has no password to change. |
    | `INTERNAL_ERROR` | 500 | Something went wrong. Details stay in the server log. |

    ## Rate limiting

    The four unauthenticated `/api/v1/auth/*` routes, plus `POST /api/v1/auth/password`, allow
    **10 requests per minute per client IP**. The password route is counted even though it needs a
    token, because it checks a password and would otherwise be somewhere to guess one. Over the
    limit the server answers `429` with a `Retry-After` header and an empty body — this is the one
    response that does not use the `ErrorResponse` shape.

    ## Field rules

    Validation runs before a request reaches the service. The schemas below give the shape; these
    are the limits:

    - `email` — a valid address, at most 320 characters, trimmed and lowercased before storage.
    - `password` — 8 to 128 characters, and at most 72 bytes in UTF-8, because BCrypt ignores
      everything past that rather than failing.
    - `displayName` — optional, at most 120 characters, never blank when present.
    - `avatarUrl` — optional, at most 512 characters, and an absolute `https` URL when present.
    - `currentPassword` — required, at most 128 characters. It is only compared against the stored
      hash, never against the rules above, so a password predating a policy change can still be
      used to replace itself.
    - `newPassword` — the `password` rules above, and it must differ from `currentPassword`.
    - `token` / `refreshToken` — at most 8192 characters.
    """.trimIndent()

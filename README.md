# ktor-sample

This project was created using the [Ktor Project Generator](https://start.ktor.io).

Here are some useful links to get you started:
 * [Ktor Documentation](https://ktor.io/docs/home.html)
 * [Ktor GitHub page](https://github.com/ktorio/ktor)
 * [Ktor Slack chat](https://app.slack.com/client/T09229ZC6/C0A974TJ9). [Request an invite](https://surveys.jetbrains.com/s3/kotlin-slack-sign-up).


## Features
Here's a list of features included in this project:

| Name | Description |
|------|-------------|
| [Status Pages](https://start.ktor.io/p/io.ktor/server-status-pages) | Provides exception handling for routes |
| [Call Logging](https://start.ktor.io/p/io.ktor/server-call-logging) | Logs client requests |
| [kotlinx.serialization](https://start.ktor.io/p/io.ktor/server-kotlinx-serialization) | Handles JSON serialization using kotlinx.serialization library |
| [Content Negotiation](https://start.ktor.io/p/io.ktor/server-content-negotiation) | Provides automatic content conversion according to Content-Type and Accept headers |
| [PostgreSQL](https://start.ktor.io/p/org.jetbrains/server-postgres) | Adds Postgres database support |


## Authentication

Sign-in is JWT based. The mobile app obtains a provider credential with the native Google or
Facebook SDK and exchanges it here for our own tokens; the backend never runs an OAuth redirect.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/api/v1/auth/register` | public | Create an account with an email and password |
| POST | `/api/v1/auth/login` | public | Exchange an email and password for tokens |
| POST | `/api/v1/auth/social` | public | Exchange a Google id_token or Facebook access token for tokens |
| POST | `/api/v1/auth/refresh` | public | Rotate a refresh token |
| POST | `/api/v1/auth/password` | access token | Replace the caller's password and drop every other session |
| POST | `/api/v1/auth/logout` | access token | Revoke the presented refresh token |
| POST | `/api/v1/auth/logout-all` | access token | Revoke every refresh token for the caller |
| POST | `/api/v1/auth/link` | access token | Attach a provider account to the signed-in user |
| GET | `/api/v1/users/me` | access token | The caller's own profile |
| PUT | `/api/v1/users/me` | access token | Replace the caller's display name and avatar |

Access tokens live 15 minutes. Refresh tokens are opaque, stored only as a SHA-256 digest, and
rotate on every use: presenting a token that was already spent revokes the whole login.

### Configuration

Nothing secret lives in `application.yaml` — it only names the environment variables. The
application refuses to start when a required one is missing.

| Variable | Required | Default | Notes |
|---|---|---|---|
| `PORT` | no | `8080` | Port the server binds to; `APP_PORT` publishes it on the host |
| `JWT_SECRET` | yes | — | HMAC256 signing key for access tokens |
| `JWT_ISSUER` | no | `com.example.ktor-sample` | |
| `JWT_AUDIENCE` | no | `com.example.mobile` | |
| `JWT_REALM` | no | `ktor-sample` | |
| `JWT_ACCESS_TOKEN_MINUTES` | no | `15` | |
| `JWT_REFRESH_TOKEN_DAYS` | no | `30` | |
| `DATABASE_URL` | yes | — | JDBC url of the Postgres instance |
| `DATABASE_USER` | yes | — | |
| `DATABASE_PASSWORD` | yes | — | |
| `DATABASE_MAX_POOL_SIZE` | no | `10` | |
| `DATABASE_RUN_MIGRATIONS` | no | `true` | Flyway runs at startup |
| `GOOGLE_CLIENT_IDS` | no | — | Comma separated; enables Google when set |
| `FACEBOOK_APP_ID` | no | — | Enables Facebook when set with the secret below |
| `FACEBOOK_APP_SECRET` | no | — | |
| `CORS_ALLOWED_ORIGINS` | no | none | Comma separated exact origins |
| `BCRYPT_COST` | no | `12` | |

Route and repository tests run against a real Postgres through Testcontainers, so a running Docker
daemon is needed; they are skipped rather than failed when there is none.

## Shared API contract (`:shared`)

The request and response models, the error shape, the field limits and every route path live in
`:shared`, a Kotlin Multiplatform module the KMP app depends on. One definition serves both sides,
so a field that is renamed here fails the mobile build instead of failing in production.

```
shared/src/commonMain/kotlin/com/example/api/
├── ApiRoutes.kt              # every path, with the /api/v1 prefix already applied
├── auth/AuthDto.kt           # RegisterRequest, LoginRequest, SocialSignInRequest, …
├── auth/SocialProvider.kt
├── user/UserResponse.kt
├── common/ErrorResponse.kt   # the error body plus the ErrorCode values a client branches on
└── common/FieldLimits.kt     # the bounds the server enforces, so the app can check them first
```

What deliberately stays on the server: the Exposed tables, the `User` domain object, the
`AppException` hierarchy and the mapping from an exception to a status code. The module publishes
the wire contract, not the backend's internals.

### Consuming it from the app

The module publishes five targets: `jvm` (which is also what an Android consumer resolves), `js`
and `wasmJs` for a web client, and `iosArm64` plus `iosSimulatorArm64` for the iOS app. A host that
cannot build one of the native targets skips it instead of failing, so a Linux CI run still builds
the module — publish the Apple artifacts from a Mac.

```bash
./gradlew :shared:publishToMavenLocal
```

That publishes `com.example:api-contract:1.0.0-SNAPSHOT`. In the app:

```kotlin
commonMain.dependencies {
    implementation("com.example:api-contract:1.0.0-SNAPSHOT")
}
```

```kotlin
val response = httpClient.post(ApiRoutes.Auth.LOGIN) {
    contentType(ContentType.Application.Json)
    setBody(LoginRequest(email, password))
}
```

While both repositories are checked out side by side, a composite build skips the publish step
entirely — add `includeBuild("../ktor-sample")` to the app's `settings.gradle.kts`.

## Development environment

`compose.dev.yaml` starts only the backing services, so the application itself runs on the host and
an iteration is a Gradle task instead of an image build. Use `compose.yaml` instead when you want
the packaged application in a container as well.

### Starting the server

Two things have to be up: Postgres in Docker, and the application on the host. `docker compose up`
starts the first only — nothing answers on port 8080 until you also run `./gradlew run`.

```bash
cp env.example .env          # once; then fill in JWT_SECRET, POSTGRES_PASSWORD and DATABASE_PASSWORD
docker compose -f compose.dev.yaml up -d      # Postgres on 127.0.0.1:5432,
                                              # Adminer on http://127.0.0.1:8081
set -a; source .env; set +a                   # export the variables the app reads
./gradlew run                                 # Flyway migrates on startup
```

The application never reads `.env` itself — it reads the environment. `set -a; source .env; set +a`
exports it into the current shell only, so repeat that line in every new terminal you start the
server from. Miss it and startup stops on the first missing variable:

```
Missing required configuration 'jwt.secret'. Set the JWT_SECRET environment variable.
```

It is serving once the log reaches these two lines:

```
[main] INFO  Application - Application started in 0.864 seconds.
[DefaultDispatcher-worker-1] INFO  Application - Responding at http://0.0.0.0:8080
```

Then open <http://127.0.0.1:8080/docs>. `Ctrl-C` stops the application; Postgres keeps running until
you bring the compose stack down, so the next `./gradlew run` starts against the same data.

| Task | Description |
|------|-------------|
| `docker compose -f compose.dev.yaml ps` | Show the service and its health |
| `docker compose -f compose.dev.yaml logs -f db` | Follow the database log |
| `open http://127.0.0.1:8081` | Adminer; log in with the `POSTGRES_*` values from `.env` |
| `docker compose -f compose.dev.yaml down` | Stop, keeping the data |
| `docker compose -f compose.dev.yaml down -v` | Stop and drop the development data |

### When it will not start

| Symptom | Cause |
|---|---|
| `ERR_CONNECTION_REFUSED` on `/docs` | The application is not running. Compose being up is not enough — start it with `./gradlew run`. |
| `Missing required configuration '...'` | The variables were not exported in this shell. Re-run `set -a; source .env; set +a`. |
| `Connection to localhost:5432 refused` | Postgres is down. `docker compose -f compose.dev.yaml ps` should show `db` as `healthy`. |
| `Address already in use` | Something else holds 8080. `lsof -nP -iTCP:8080 -sTCP:LISTEN` names it. |
| `No 'Access-Control-Allow-Origin' header is present` in a browser console | The page's origin is not in `CORS_ALLOWED_ORIGINS`, so the server refuses the preflight with a bare `403`. Add the exact scheme + host + port, re-export, and restart. |

`PORT` in `.env` moves it — `./gradlew run` reads the environment, so `PORT=9090` and a re-run of
`set -a; source .env; set +a` is the whole change. `APP_PORT` is a different setting: it is the
*host* side of `compose.yaml`'s port mapping, whose container side follows `PORT`, and it has no
effect on `./gradlew run`.

## API documentation

The server documents itself, in the style of FastAPI:

| URL | What it is |
|---|---|
| `http://localhost:8080/docs` | Swagger UI. Browse every endpoint and call it with **Try it out**. |
| `http://localhost:8080/openapi.json` | The raw OpenAPI 3.1 document, for client generators and Postman. |

Both are public on every environment.

To call a protected endpoint from the page, sign in through `POST /api/v1/auth/register` or
`POST /api/v1/auth/login`, copy `accessToken` out of the response, then press **Authorize** at the
top right and paste it.

### How the specification is produced

There is no specification file. The Ktor OpenAPI compiler plugin (`ktor { openApi { } }` in
`build.gradle.kts`) reads the routing tree at compile time, and `OpenApiDocSource.Routing` assembles
the document at runtime. Generated for you, with no way to drift:

- every path and HTTP method,
- request and response schemas, from the `@Serializable` DTOs themselves,
- which endpoints need a token, from the `authenticate("auth-jwt")` blocks.

What the compiler cannot know you write next to the route in a `describe { }` block: the summary,
the prose, the failures `StatusPages` produces, and the worked examples in the **Examples** dropdown
above each request body. Both come from shared helpers in `common/OpenApi.kt` — the error contract is
declared once rather than per route, and `jsonBody(...)` takes examples as real DTO instances, so a
renamed field breaks the build instead of quietly leaving a stale example on the page.

The API-wide preamble — authentication guide, conventions, error-code table, rate limits, field
limits — lives in `plugins/Docs.kt`.

**Adding an endpoint?** Give it a `describe { }` block with at least a `summary` and a `tag`, and a
`requestBody { jsonBody(...) }` if it takes one. `DocsRoutesTest` fails and names your route if you
forget either. It also pins exactly which endpoints require a token, so moving a route in or out of
`authenticate` is a deliberate, visible change.

## Building & Running

| Task | Description |
|------|-------------|
| `./gradlew test`    | Run the tests     |
| `./gradlew build`   | Compile and test  |
| `./gradlew run`     | Run the server on :8080 |
| `./gradlew ktlintFormat` | Auto-fix style |
| `./gradlew buildFatJar`  | Build the deployable jar |

`./gradlew run` needs Postgres up and the environment exported first — see
[Starting the server](#starting-the-server) for the full sequence. Running the application in
Docker, locally or on a server, is covered in [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).

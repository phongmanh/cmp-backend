# CLAUDE.md

Rules for working in this repository. Read this before writing or changing any code.

## What this project is

A Ktor (JVM) backend that serves the mobile app. Kotlin only.

| Piece | Choice |
|---|---|
| Framework | Ktor 3.x, Netty engine |
| Language | Kotlin 2.2, JDK 21 |
| Serialization | kotlinx.serialization |
| Database | PostgreSQL through Exposed + HikariCP |
| Migrations | Flyway |
| Auth | JWT (HMAC256) |
| DI | Koin |
| Logging | Logback + SLF4J |
| Tests | kotlin.test + `testApplication` + Testcontainers |

## Commands

```bash
./gradlew build            # compile + test
./gradlew test             # tests only
./gradlew run              # run locally on :8080
./gradlew ktlintCheck      # style check
./gradlew ktlintFormat     # auto-fix style
./gradlew buildFatJar      # deployable jar
```

Always run `./gradlew ktlintFormat && ./gradlew build` before saying a change is finished. Do not report success on code you have not compiled.

## Folder layout

```
src/main/kotlin/com/example/
├── Application.kt          # entry point, wires modules only
├── plugins/                # configureSerialization, configureSecurity, etc.
├── feature/
│   └── user/
│       ├── UserRoutes.kt       # HTTP layer
│       ├── UserService.kt      # business rules
│       ├── UserRepository.kt   # database access
│       ├── UserTable.kt        # Exposed table
│       └── UserDto.kt          # request/response models
└── common/                 # errors, extensions, shared helpers
```

New code goes inside a feature folder. Do not create a top-level `services/`, `controllers/`, or `models/` folder — this project groups by feature, not by layer.

## Layering rules

Traffic flows one direction only: **route → service → repository → database**.

- A route handler reads input, calls one service function, and responds. No business rules, no SQL, no transactions in a route.
- A service holds the business rules. It never touches `ApplicationCall`, `HttpStatusCode`, or anything from `io.ktor.server.*`.
- A repository is the only place that talks to Exposed. It returns domain objects, never `ResultRow`.
- A repository never calls a service. If you feel the need, the logic is in the wrong layer.

## Code style

- ktlint with the official Kotlin style. 4 spaces, 120 column limit.
- `val` everywhere unless reassignment is truly needed. Prefer immutable data classes.
- No `!!`. Handle the null case, or use `?: throw BadRequestException(...)`.
- No `lateinit` outside test setup.
- No wildcard imports.
- Public functions in service and repository classes are `suspend` when they do input/output work.
- Never use `runBlocking` in production code. `GlobalScope` is banned.
- Never use `Dispatchers.IO` directly for database work — use the `dbQuery { }` helper, which already moves the call off the request thread.
- Name booleans as questions: `isActive`, `hasExpired`.
- Comments explain *why*, not *what*. Delete a comment that only restates the code.
- No commented-out code. Git remembers it.

## API rules

- Every route lives under `/api/v1`. A breaking change means a new version folder, never a silent change to v1.
- Requests and responses use dedicated DTO classes marked `@Serializable`. Never expose an Exposed entity or an internal domain object directly on the wire.
- Never put a password hash, internal identifier, or audit column in a response DTO.
- Status codes: `200` read, `201` create with a `Location` header, `204` delete, `400` bad input, `401` missing or bad token, `403` valid token but not allowed, `404` not found, `409` conflict, `422` valid shape but broken business rule.
- All errors return the same body shape, produced by `StatusPages` in one place:

```kotlin
@Serializable
data class ErrorResponse(val code: String, val message: String)
```

- Do not add try/catch inside route handlers. Throw a typed exception and let `StatusPages` map it.
- Field names in JSON are `camelCase`. Dates are ISO-8601 strings in UTC.
- Any list endpoint is paginated from day one: `?limit=` (default 20, max 100) and `?cursor=`. Never return an unbounded list.

## Database rules

- Every database call is wrapped in `dbQuery { }`. A raw Exposed call outside it blocks a coroutine thread and will starve the server under load.
- Schema changes happen only through a new Flyway migration file. Never edit a migration that is already merged. `SchemaUtils.create` is for tests only.
- Every foreign key and every column used in a `WHERE` clause needs an index.
- Do not query inside a loop. Load with a single `inList` query and map in memory.
- A write path that touches more than one table runs inside one transaction.
- Deletes are soft (`deletedAt`) for anything a user created, unless the ticket says otherwise.

## Security rules

These are not negotiable. If a change conflicts with one of them, stop and ask instead of working around it.

**Secrets**
- No secret, token, password, connection string, or private key in source code, in `application.yaml`, or in a test fixture. Read them from environment variables through a small `Config` object that fails fast at startup if one is missing.
- Never write a real credential into an example, a comment, or a commit message.
- `.env`, `*.pem`, `*.jks`, and `local.properties` stay in `.gitignore`.

**Input**
- Validate every incoming field before it reaches the service: length, range, format, allowed values. Reject unknown enum values rather than defaulting them.
- Bind all user input as Exposed parameters. Never build SQL by string concatenation, and never pass user text into `exec()`.
- Cap request body size and reject uploads above the agreed limit.

**Authentication and authorization**
- Protected routes sit inside `authenticate("auth-jwt")`. A new route is private by default; making it public is a deliberate, reviewed decision.
- Read the caller identity from the JWT principal only. Never trust a `userId` sent in a body, a query parameter, or a header.
- Every route that touches a record owned by someone must check ownership or role. "Can this specific caller touch this specific row" is a separate question from "is this caller logged in".
- Passwords are hashed with BCrypt (cost 12) or Argon2. No SHA, no MD5, no home-made hashing.
- Access tokens are short-lived (15 minutes) with a rotating refresh token stored server-side so it can be revoked.

**Output and logging**
- Never log a token, password, full authorization header, or complete request body. Log identifiers, not payloads.
- Personal data in logs is masked: `user@example.com` becomes `u***@example.com`.
- An error returned to the client says what the client did wrong. Stack traces, SQL text, and library versions stay in the server log.
- The generic `Throwable` handler always returns a plain message, never `cause.message`.

**Transport and platform**
- HTTPS only. Set HSTS, `X-Content-Type-Options: nosniff`, and a frame-deny header.
- CORS lists exact allowed origins. `anyHost()` is banned outside local development.
- Rate limit login, registration, password reset, and any endpoint that sends a message or an email.
- Return the same response and timing for "unknown email" and "wrong password" so accounts cannot be enumerated.
- Do not add a dependency without asking. When one is approved, pin the version in the version catalog.

## Testing rules

- Every new endpoint gets at least: one success test, one invalid-input test, one unauthorized test.
- Every fixed bug gets a test that fails before the fix.
- Route tests use `testApplication`. Repository tests use Testcontainers with real Postgres — never H2, because the SQL dialects differ.
- Tests do not hit the network or a shared database.
- Test names describe behaviour in backticks: ``fun \`returns 404 when user is missing\`()``.

## Git rules

- Branches: `feature/short-name`, `fix/short-name`.
- Conventional commits: `feat:`, `fix:`, `refactor:`, `test:`, `chore:`.
- One logical change per commit. Do not mix a reformat with a behaviour change.
- Never commit directly to `main`.

## How to work with me

- If the task is unclear or the codebase contradicts this file, ask before writing code.
- Read the surrounding files first and copy their patterns. Consistency beats personal preference.
- Change the smallest amount of code that solves the problem. Do not refactor nearby code as a bonus.
- Do not delete or rewrite a test to make a build pass.
- Never run a destructive command (`git push --force`, `git reset --hard`, `DROP`, `TRUNCATE`, migration rollback on a shared database) without asking first.
- When you finish, say what changed, what you ran, and anything you were unsure about.

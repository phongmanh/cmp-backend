# Deploying the backend with Docker

Two shapes of deployment come out of this repository:

- **The whole stack** — `compose.yaml` runs the application and its Postgres together. This is the
  local deployment and the starting point for a single-box server.
- **The image alone** — the `Dockerfile` produces a self-contained jar image that talks to a Postgres
  you run elsewhere. This is what you push to a registry and what a managed platform runs.

Both use the same image. Nothing below needs a JDK on the host; the build happens inside Docker.

For iterating on code, don't use either one — `compose.dev.yaml` starts only Postgres and Adminer and
you run the server with `./gradlew run`. See [Development environment](../README.md#development-environment).

---

## 1. Configure

Every setting arrives as an environment variable. `application.yaml` names them and holds no values,
and the application refuses to start when a required one is missing rather than booting half
configured.

```bash
cp env.example .env
```

Fill in the two blanks:

| Variable | Generate with | Why it matters |
|---|---|---|
| `JWT_SECRET` | `openssl rand -base64 48` | Signs every access token. Changing it invalidates all outstanding tokens. |
| `POSTGRES_PASSWORD` | `openssl rand -hex 16` | Used by both the `db` service and the application. |

`PUBLIC_BASE_URL` is also required and ships with a working local value. Change it for any
deployment a phone reaches: it is the address clients dial, not the one the container binds, and
every avatar URL the API publishes is built on it.

`.env` is git-ignored and must stay that way — it is the one file in the tree that holds real
credentials. The full variable list, including the optional ones, is in the
[README configuration table](../README.md#configuration).

Compose refuses to start rather than defaulting a secret, so a missing value fails immediately and
by name:

```
error while interpolating services.db.environment.POSTGRES_PASSWORD: required variable POSTGRES_PASSWORD is missing a value: set POSTGRES_PASSWORD in .env
```

Social sign-in stays off unless configured. Facebook is the one case that fails loudly instead of
staying quiet: setting `FACEBOOK_APP_ID` without `FACEBOOK_APP_SECRET` (or the reverse) aborts
startup, because a half-configured provider is a deployment mistake rather than a choice.

---

## 2. Build and start

```bash
docker compose up -d --build
```

The first build takes a few minutes — it downloads the Gradle distribution and every dependency.
Later builds reuse both unless a build file changed.

What happens, in order:

1. `db` starts and is not considered up until `pg_isready` passes.
2. `app` starts only once `db` reports healthy, so the application never races the database.
3. Flyway applies pending migrations.
4. Netty binds `0.0.0.0:8080` inside the container, published on `127.0.0.1:8080` of the host.
   `PORT` changes what it binds — `compose.yaml` passes it through and maps `APP_PORT` to whatever
   `PORT` says, so the two ends of the mapping cannot drift apart. Restricting exposure is the
   `127.0.0.1` in the published port.

A healthy startup looks like this:

```
Applying database migrations
Successfully validated 2 migrations (execution time 00:00.009s)
Migrating schema "public" to version "1 - create auth tables"
Migrating schema "public" to version "2 - add user avatar url"
Successfully applied 2 migrations to schema "public", now at version v2
Application started in 0.839 seconds.
Responding at http://0.0.0.0:8080
```

Confirm it from outside the container:

```bash
docker compose ps                      # app should read "healthy", not just "running"
open http://localhost:8080/docs        # Swagger UI, and every endpoint is callable from it
```

`healthy` is the signal worth waiting for. The container's own healthcheck polls `/` every 10
seconds after a 40 second grace period and gives up after 5 failures, so `running` can still mean
the process is up but not serving.

---

## 3. What is in the image

The `Dockerfile` is two-stage, and the split is the point: the build stage has a JDK, Gradle, and
your sources; the runtime stage has a JRE and one jar.

| Property | Value | Why |
|---|---|---|
| Build base | `eclipse-temurin:21-jdk` | The Gradle wrapper pins Gradle, so the image only supplies the JDK. |
| Runtime base | `eclipse-temurin:21-jre` | No compiler, no Gradle, no sources in the shipped image. |
| Size | ~540 MB | Almost all of it is the JRE base layer, shared between rebuilds. |
| User | `app`, non-root | A process that never needs to write to the filesystem should not be able to. |
| Entrypoint | `java -XX:MaxRAMPercentage=75.0 -jar /app/app.jar` | Keeps the heap inside whatever memory limit the container is given, instead of reading the host's total. |
| Healthcheck | `curl` on `/` | What Compose's `condition: service_healthy` and most orchestrators watch. |

Two details are easy to trip over when editing it:

**Both modules are copied.** This is a two-module Gradle build — the server depends on `:shared`,
the published API contract. Build files (`settings.gradle.kts`, `build.gradle.kts`,
`shared/build.gradle.kts`) are copied before sources so a Kotlin-only change reuses the cached
dependency layer, and both `src/` and `shared/src/` are copied after. Forgetting the `shared` copies
fails the build with `Configuring project ':shared' without an existing directory is not allowed`.

**Tests do not run in the image build.** The build stage runs `buildFatJar -x test` because
repository and route tests use Testcontainers, which needs a Docker daemon the build does not have.
Run `./gradlew build` on your machine or in CI; a green image build is not a green test run.

---

## 4. Everyday operations

| Task | Command |
|---|---|
| Follow the application log | `docker compose logs -f app` |
| Service state and health | `docker compose ps` |
| Redeploy after a code change | `docker compose up -d --build app` |
| Restart without rebuilding | `docker compose restart app` |
| Stop, keeping the data | `docker compose down` |
| Stop and drop the database | `docker compose down -v` |
| Open a psql session | `docker compose exec db psql -U ktor -d ktor_sample` |
| Shell inside the app container | `docker compose exec app sh` |

`down -v` deletes the `postgres-data` volume and everything in it. It is the right command for
resetting a local environment and the wrong one everywhere else.

---

## 5. Database migrations

Flyway runs at startup, before the server accepts traffic, and the schema is owned entirely by the
files in `src/main/resources/db/migration/`. Deploying a schema change means adding a migration file
and redeploying — there is no separate migration step to remember.

- **Adding one:** create `V3__short_description.sql`. Never edit a migration that has already run
  anywhere; Flyway stores a checksum and will refuse to start against a modified file.
- **Concurrent starts are safe.** Flyway locks the schema history table, so with several replicas
  one applies the migrations and the others wait rather than colliding.
- **`DATABASE_RUN_MIGRATIONS=false`** turns this off, for when a deployment pipeline applies
  migrations as its own step. The application then assumes the schema is already current.
- A long migration delays startup for every replica. Once one is slow enough to matter, run it as a
  separate job before the rollout instead.

---

## 6. Deploying to a server

Build, push, and run the image; point it at a Postgres you manage.

```bash
# Build for the architecture the server actually runs. A Mac builds arm64 by default,
# and most cloud VMs are amd64 — an image built without this flag will not start there.
docker buildx build --platform linux/amd64 -t registry.example.com/ktor-sample:1.0.0 .
docker push registry.example.com/ktor-sample:1.0.0
```

On the server:

```bash
docker run -d --name ktor-sample \
  --restart unless-stopped \
  -p 127.0.0.1:8080:8080 \
  -e JWT_SECRET \
  -e DATABASE_URL="jdbc:postgresql://db.internal:5432/ktor_sample" \
  -e DATABASE_USER=ktor \
  -e DATABASE_PASSWORD \
  -e CORS_ALLOWED_ORIGINS="https://app.example.com" \
  -e PUBLIC_BASE_URL="https://api.example.com" \
  registry.example.com/ktor-sample:1.0.0
```

Passing `-e VAR` with no value forwards it from the shell's environment, which keeps the secret out
of your shell history and out of the command line other users can see in `ps`.

Tag with a real version rather than `latest`. A rollback is only as easy as your ability to name the
image that worked.

---

## 7. Before this faces the internet

`compose.yaml` is a working local deployment, not a hardened one. Each of these is a deliberate gap:

- **TLS terminates somewhere else.** The container speaks plain HTTP. Put a reverse proxy in front
  of it. The application already sends HSTS, `X-Content-Type-Options: nosniff`, `X-Frame-Options:
  DENY` and `Referrer-Policy`, but HSTS only means anything once the connection is HTTPS.
- **Ports are bound to `127.0.0.1`.** Nothing is reachable from the network until you put a proxy on
  the same host or change the binding on purpose. That default is worth keeping.
- **The auth rate limiter needs help behind a proxy.** It keys on the socket's peer address, and
  `ForwardedHeaders` is not installed, so behind a reverse proxy every caller shares one bucket —
  ten sign-in attempts a minute across all of your users. Install `ForwardedHeaders` and configure
  it to trust only your proxy before relying on that limit in production.
- **`/docs` and `/openapi.json` are public.** They describe the API to anyone who asks. Fine for an
  internal or intentionally public API; a decision to make consciously otherwise.
- **Environment variables are not secrets management.** Anything passed with `-e` is visible in
  `docker inspect` and to anyone who can talk to the daemon. Use Docker secrets, or your platform's
  secret store, once this leaves your machine.
- **The bundled `db` service has no backups.** It is a container with a volume — no point-in-time
  recovery, no failover. Use a managed Postgres for anything whose loss would matter, and drop the
  `db` service from the compose file when you do.
- **Size the connection pool against the database.** `DATABASE_MAX_POOL_SIZE` defaults to 10 *per
  instance*. Multiply by your replica count and compare against the server's `max_connections`.
- **`CORS_ALLOWED_ORIGINS` takes exact origins.** Empty means no browser origin is allowed at all,
  which is the correct setting for a backend only a mobile app calls.

---

## 8. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `required variable JWT_SECRET is missing a value` | No `.env`, or the value is blank | `cp env.example .env` and fill both blanks |
| `Missing required configuration 'database.url'. Set the DATABASE_URL environment variable.` | The variable never reached the container | Check the `environment:` block, or the `-e` flags on `docker run` |
| `Missing required configuration 'app.publicBaseUrl'.` | `PUBLIC_BASE_URL` is not set | Set it to the public origin, no trailing slash |
| Avatars load as broken images in the app | `PUBLIC_BASE_URL` names the container rather than the public host | It is the address a phone dials, not the one the container binds. Set and restart |
| `Facebook is half configured` | One of the two Facebook variables is set | Set both, or neither to disable the provider |
| `Bind for 127.0.0.1:8080 failed: port is already allocated` | Something else holds the port | Set `APP_PORT` or `POSTGRES_PORT` in `.env` |
| Container never turns `healthy` after setting `PORT` | — | The healthcheck follows `PORT`; make sure the platform routes to that same port |
| App container never turns `healthy` | Usually the database | `docker compose logs app`; confirm `db` is healthy first |
| `Configuring project ':shared' without an existing directory` | The Dockerfile is missing the `shared/` copies | See [What is in the image](#3-what-is-in-the-image) |
| `Validate failed: Migration checksum mismatch` | An already-applied migration file was edited | Restore the file; put the change in a new migration |
| `exec format error` on the server | Image built for the wrong architecture | Rebuild with `--platform linux/amd64` |

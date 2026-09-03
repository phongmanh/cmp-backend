# Deploying the backend on Vercel

Vercel runs OCI container images as Functions, so the JVM needs no special support from the
platform — the image built here is the same shape as the one in [`Dockerfile`](../Dockerfile). What
differs is everything around it: the container is started on demand, stopped when idle, and handed
an environment the platform composes itself.

For the Docker and Compose deployment, see [Deploying with Docker](DEPLOYMENT.md). That is still the
right target for a single box you control; this one trades steady-state latency for not owning a
server.

---

## 1. What is in the repository

| File | Role |
|---|---|
| `Dockerfile.vercel` | The image Vercel builds. Same two stages as `Dockerfile`, minus the healthcheck and the BuildKit cache mounts. |
| `docker-entrypoint.vercel.sh` | Converts a `postgres://` URL into the JDBC form the application reads, and repairs `PATH`. |
| `vercel.json` | Declares one container service and the rewrite that exposes it. |

`vercel.json` is deliberately small:

```json
{
  "services": {
    "api": { "root": ".", "runtime": "container", "entrypoint": "Dockerfile.vercel" }
  },
  "rewrites": [{ "source": "/(.*)", "destination": { "service": "api" } }]
}
```

**A service without a top-level rewrite is private.** It builds, it deploys, and nothing can reach
it. The catch-all rewrite is what makes the API public, not the `services` entry.

---

## 2. Two things the platform does that a local `docker run` does not

Both of these were found by deploying, not by testing locally, because locally they cannot happen.

**`PATH` is not inherited from the image.** `docker run` gives the process the `ENV PATH` baked into
`eclipse-temurin`, which includes `/opt/java/openjdk/bin`. Vercel composes the environment itself,
and `java` is not on it — the container exits `127` with `exec: java: not found` before a single
line of Kotlin runs. The entrypoint rebuilds `PATH` from `JAVA_HOME`, with the temurin path
hardcoded as a fallback in case that is missing too.

**Port 80 is the default, and a non-root process cannot bind it.** The image runs as `app`, so the
deployment sets `PORT=8080` and the application binds that. Both ends have to agree: Vercel routes
to whatever `PORT` says, and `application.yaml` reads the same variable.

---

## 3. Configuration

Everything arrives as an environment variable, set per environment in the project settings.

| Variable | Value | Why |
|---|---|---|
| `PORT` | `8080` | Where Vercel routes and where Netty binds. Not optional: the default is 80, which a non-root process cannot take. |
| `DATABASE_URL` | `postgresql://…` | The Postgres connection string. The entrypoint converts it; see below. |
| `DATABASE_MAX_POOL_SIZE` | `3` | Per instance, and instances multiply. See [Sizing the pool](#5-the-database). |
| `TRUST_PROXY_HEADERS` | `true` | Makes the auth rate limiter read the real client IP. See below. |
| `JWT_SECRET` | `openssl rand -base64 48` | Use a different value per environment, and never the one from your laptop's `.env`. |

`JWT_ISSUER`, `JWT_AUDIENCE`, `JWT_REALM`, `CORS_ALLOWED_ORIGINS` and the social provider variables
behave exactly as they do in the Docker deployment.

### `TRUST_PROXY_HEADERS` is a security setting, not a convenience

The auth rate limiter keys on the caller's address. Behind a proxy every request arrives from the
proxy's socket, so without help the whole internet shares one bucket — ten sign-in attempts a minute
across all users, and one attacker locks out everyone.

Setting `TRUST_PROXY_HEADERS=true` installs `XForwardedHeaders` so the limiter reads
`X-Forwarded-For` instead. That is only safe because [Vercel overwrites that header and refuses to
forward external IPs](https://vercel.com/docs/headers/request-headers) specifically to prevent
spoofing. **Leave it `false` anywhere the application is reachable without a proxy that does the
same**, or the header becomes a free supply of fresh rate-limit buckets.

---

## 4. Deploying

```bash
npm i -g vercel
vercel login
vercel link
vercel deploy          # preview
vercel deploy --prod   # production
```

A build takes roughly three minutes. Vercel builds the image, pushes it to Vercel Container
Registry, and serves it from a Function. `Build output contains no "functions" or "static"
directory` in the log is expected for a container service — the image is the output.

Preview deployments sit behind Deployment Protection, so a plain `curl` gets a login page. Use
`vercel curl <url>`, which mints a bypass token.

```bash
vercel logs <url>       # runtime logs, from the container's stdout and stderr
vercel inspect <url>    # build, size, and region
```

Logs appear only after something has invoked the container. A deployment that has never been
requested has no logs, which is not the same as a deployment that failed silently.

---

## 5. The database

The application reads `DATABASE_URL` as a **JDBC** URL and takes the user and password separately.
Every managed Postgres hands out a **libpq** URL with the credentials inline instead:

```
postgresql://user:password@ep-x.eu-central-1.aws.neon.tech/neondb?sslmode=require
```

The two collide under the same name, so `docker-entrypoint.vercel.sh` derives all three at start-up
rather than having the secret copied into a second set of variables that drifts on the next
rotation. It percent-decodes the credentials, drops libpq-only parameters such as `channel_binding`
that the JDBC driver does not understand, and re-states `sslmode=require`.

Consequences worth knowing:

- **It always requires TLS.** Correct for a managed provider, wrong for a plain local Postgres.
- **A `jdbc:` URL is left untouched**, credentials included. That is the override.
- **Credentials come from the URL that carried them.** A `DATABASE_USER` left over from elsewhere is
  ignored rather than paired with a host it does not belong to.

### Use the direct endpoint, not the pooled one

Neon's pooled endpoint — the host with `-pooler` in it — is PgBouncer in transaction pooling mode,
which does not preserve session state between statements. Flyway takes a **session level advisory
lock** while it migrates. Through the pooler that lock does not hold, and concurrent starts can race
on the schema. Point at the direct endpoint and keep `DATABASE_MAX_POOL_SIZE` small instead;
HikariCP is already pooling in front of it.

### Sizing the pool

`DATABASE_MAX_POOL_SIZE` is per instance, and Vercel runs as many instances as traffic demands.
Multiply by the instances you expect and compare against the database's connection limit. Three is a
reasonable starting point; ten — the application default — is not.

### Region

Check where the Function runs (`vercel inspect`, and the region prefix in an error ID such as
`hkg1:hkg1::…`) against where the database lives. A container in Hong Kong talking to a database in
Virginia pays a round trip across the Pacific on every query.

---

## 6. What scaling to zero costs you

A Function with no traffic for **5 minutes in production, 30 seconds in preview** is scaled down. On
shutdown the container gets `SIGTERM` and 30 seconds to finish.

The next request after that pays for a cold start: image pull, JVM boot, Koin, the connection pool,
and Flyway. The application itself starts in under a second; the rest is platform and JVM overhead.
If the database also sleeps — Neon's free tier scales its compute to zero as well — the first
request wakes both, and HikariCP's connection timeout is 30 seconds. A 500 on the first request
after a quiet period is usually this, not a misconfiguration.

**Flyway runs on every cold start.** It is safe: Flyway locks the schema history table, so several
instances starting at once produce one migration and some waiting. It is not free, and it puts a
database round trip in front of the first request each time an instance starts. Once a migration is
slow enough to notice, set `DATABASE_RUN_MIGRATIONS=false` and run migrations as their own step
before rolling out.

---

## 7. Before this faces real traffic

Most of [the Docker deployment's list](DEPLOYMENT.md#7-before-this-faces-the-internet) still applies.
What Vercel changes:

- **TLS is handled.** Vercel terminates HTTPS, so HSTS now means something.
- **The rate limiter needs `TRUST_PROXY_HEADERS=true`** to be a per-user limit rather than a global
  one. This is the one item that is worse by default here than on a box you control.
- **`/docs` and `/openapi.json` are public**, and a preview URL is only as private as Deployment
  Protection makes it.
- **Environment variables are visible to anyone with project access.** Rotate anything that has
  ever been in a local `.env`, and never reuse a development signing key.
- **Secure Compute and static IPs are not available** for container images, so a database that
  allow-lists source addresses cannot be locked down that way.

---

## 8. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `FUNCTION_INVOCATION_FAILED`, exit `127`, `exec: java: not found` | `PATH` did not survive into the container | The entrypoint rebuilds it from `JAVA_HOME`; check that edit is still present |
| `Missing required configuration 'database.url'` | `DATABASE_URL` is not set for this environment | Set it, and confirm with `vercel env ls` that it lists the environment you deployed |
| `Module function cannot be found for the fully qualified name …` | A module in `application.yaml` does not match the compiled class | The file is missing its `package` declaration, or sits outside `com/example/` |
| Deployment `Ready` but every request 500s | The container crashes at start-up | `vercel logs <url>` after making a request; a container that was never invoked logs nothing |
| A plain `curl` returns a login page | Deployment Protection | `vercel curl <url>` |
| First request after idle times out | Cold start plus a sleeping database | Expected on free tiers; keep a warm instance or accept it |
| `Validate failed: Migration checksum mismatch` | An applied migration file was edited | Restore it; put the change in a new migration |

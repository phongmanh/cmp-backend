#!/bin/sh
# Bridges Vercel's environment to the one the application expects. Two jobs: the database
# credentials, and the public base URL.
#
# Neon injects DATABASE_URL as a libpq URL that carries the credentials inline:
#
#   postgresql://user:password@ep-x.eu-central-1.aws.neon.tech/dbname?sslmode=require
#
# The application reads DATABASE_URL as a JDBC URL and takes the user and password separately, so
# the two collide under the same name. Rather than copy the secret into a second set of variables
# by hand — which then drifts the moment Neon rotates it — this derives them at start-up.
#
# To point at a database other than the injected one, set DATABASE_URL to a jdbc: URL: this leaves
# a JDBC URL and its DATABASE_USER and DATABASE_PASSWORD untouched.
#
# The second job is PUBLIC_BASE_URL, which a preview cannot be told ahead of time; see below.
set -eu

# Vercel's container runtime does not carry the image's own ENV PATH into the process it starts, so
# `java` is unresolvable there even though the base image puts it on PATH and `docker run` locally
# does inherit it. Rebuild PATH from JAVA_HOME instead of trusting what we were handed.
PATH="${JAVA_HOME:-/opt/java/openjdk}/bin:${PATH:-/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin}"
export PATH

log() {
    echo "entrypoint: $*" >&2
}

# Percent-decoding matters: Neon passwords are generated and can contain characters that are
# escaped in a URL but must not stay escaped in a JDBC property.
urldecode() {
    printf '%b' "$(printf '%s' "$1" | sed 's/+/ /g; s/%\(..\)/\\x\1/g')"
}

# The unpooled endpoint on purpose. Flyway holds a session level advisory lock while it migrates,
# and PgBouncer in transaction pooling mode — which is what Neon's pooled endpoint runs — does not
# keep session state across statements, so that lock would not hold. HikariCP does its own pooling
# in front of this anyway; keep DATABASE_MAX_POOL_SIZE small instead, because every scaled up
# instance opens its own pool.
source_url="${DATABASE_URL_UNPOOLED:-${DATABASE_URL:-}}"

case "$source_url" in
    postgres://* | postgresql://*)
        rest="${source_url#*://}"
        credentials="${rest%%@*}"
        location="${rest#*@}"

        # Strip the query string rather than carry it over: Neon sets libpq-only parameters such as
        # channel_binding that mean nothing to the JDBC driver. TLS is re-stated below.
        location="${location%%\?*}"
        host_and_port="${location%%/*}"
        database="${location#*/}"

        user="$(urldecode "${credentials%%:*}")"
        password="$(urldecode "${credentials#*:}")"

        # All three come from the URL together. Deferring to a DATABASE_USER that is already set
        # would pair this host with a username left over from somewhere else, which is never what
        # the deployment meant; an override belongs in DATABASE_URL itself, as a jdbc: URL.
        export DATABASE_URL="jdbc:postgresql://${host_and_port}/${database}?sslmode=require"
        export DATABASE_USER="$user"
        export DATABASE_PASSWORD="$password"

        log "database resolved to ${host_and_port}/${database} as ${user}"
        ;;
    jdbc:*)
        log "DATABASE_URL is already a JDBC URL, leaving it alone"
        ;;
    "")
        # Say nothing more: the application's own configuration check names the missing variable.
        log "no database URL in the environment"
        ;;
    *)
        log "DATABASE_URL is neither a postgres:// nor a jdbc: URL, leaving it alone"
        ;;
esac

# Where this deployment is reached from the outside. The application requires PUBLIC_BASE_URL and
# refuses to start without it: behind a proxy the request it sees names the container, not the host
# the phone dialled, so it cannot work this out for itself. See Config.kt.
#
# An explicit value always wins, which is what production wants: the canonical name is a deliberate
# choice and has to survive a new alias or a custom domain. A preview has no such fixed name, so
# derive its own. Giving every environment one project-wide value is the trap here — previews then
# publish avatar URLs on the production host, which never received the upload.
if [ -n "${PUBLIC_BASE_URL:-}" ]; then
    log "public base URL set explicitly, leaving it alone"
else
    case "${VERCEL_ENV:-}" in
        production)
            public_host="${VERCEL_PROJECT_PRODUCTION_URL:-}"
            ;;
        # The branch alias outlives any single deployment, so a URL handed out by an earlier preview
        # still resolves. VERCEL_URL changes with every push.
        preview)
            public_host="${VERCEL_BRANCH_URL:-${VERCEL_URL:-}}"
            ;;
        *)
            public_host=""
            ;;
    esac

    if [ -n "$public_host" ]; then
        # Vercel terminates TLS for every deployment domain, so the scheme is never in doubt.
        export PUBLIC_BASE_URL="https://${public_host}"
        log "public base URL derived as ${PUBLIC_BASE_URL}"
    else
        # Name the inputs: the application's own check reports only that the variable is missing,
        # which is the wrong half of the story when it was meant to be derived here.
        log "cannot derive a public base URL (VERCEL_ENV=${VERCEL_ENV:-unset}, VERCEL_URL=${VERCEL_URL:-unset})"
    fi
fi

exec "$@"

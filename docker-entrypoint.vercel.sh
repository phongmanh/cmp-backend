#!/bin/sh
# Bridges the Neon integration's environment to the one the application expects.
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

exec "$@"

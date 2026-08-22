# syntax=docker/dockerfile:1

# Build stage. The Gradle wrapper pins the Gradle version, so the image only supplies the JDK.
FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace

# Build files first: they change far less often than sources, so the Gradle distribution and the
# dependency download stay cached when only Kotlin code changes.
COPY gradlew ./
COPY gradle/ gradle/
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
# The build is a two module one: the server depends on :shared, so its build file belongs in the
# cached layer and its sources next to the server's.
COPY shared/build.gradle.kts shared/
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --quiet --version

COPY shared/src/ shared/src/
COPY src/ src/
# Tests need a Docker daemon for Testcontainers, which is not available inside a build.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --quiet buildFatJar -x test \
    && cp build/libs/*-all.jar /workspace/app.jar

# Runtime stage. JRE only, no build tooling, no sources.
FROM eclipse-temurin:21-jre AS runtime

RUN groupadd --system app && useradd --system --gid app --home /app app

WORKDIR /app
COPY --from=build --chown=app:app /workspace/app.jar /app/app.jar

USER app
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD curl --fail --silent http://127.0.0.1:8080/ || exit 1

# MaxRAMPercentage keeps the heap inside whatever limit the container is given.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]

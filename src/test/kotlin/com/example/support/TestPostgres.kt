package com.example.support

import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Repository and route tests run against a real Postgres, because H2 answers differently on the
 * things this schema leans on: partial unique indexes and UUID columns.
 */
object TestPostgres {
    val isDockerAvailable: Boolean by lazy {
        runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
    }

    val container: PostgreSQLContainer<Nothing> by lazy {
        PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine")).apply {
            withReuse(false)
            start()
        }
    }
}

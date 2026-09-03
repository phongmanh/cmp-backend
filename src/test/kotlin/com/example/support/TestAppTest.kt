package com.example.support

import io.ktor.client.request.get
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * Guards the harness rather than the server. When each test application opened and closed its own
 * pool, a request could reach a pool an earlier test had already closed — Exposed resolves the
 * database through a thread local that outlives the application — and the failure surfaced on a
 * different, innocent test on every run.
 */
class TestAppTest {
    @Test
    fun `every test application shares one database`() {
        val databases = mutableListOf<Database>()

        repeat(2) {
            authTestApplication {
                // The application block runs on the first call, so connect before reading.
                client.get("/openapi.json")

                databases += assertNotNull(TransactionManager.defaultDatabase, "no database was connected")
            }
        }

        assertSame(databases.first(), databases.last(), "a test application connected a database of its own")
    }
}

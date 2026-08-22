package com.example

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerTest {
    @Test
    fun `test root endpoint`() =
        testApplication {
            // An empty configuration keeps the database backed modules in application.yaml out of it.
            environment { config = MapApplicationConfig() }
            // The root route is the only thing under test here, so the application is not booted
            // with the database backed modules.
            application { configureRouting() }
            // verify server root returns 200
            assertEquals(HttpStatusCode.OK, client.get("/").status)
        }
}

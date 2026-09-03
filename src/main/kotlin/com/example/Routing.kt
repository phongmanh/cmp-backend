package com.example

import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.routing

fun Application.configureRouting() {
    routing {
        // Scaffolding from the project template, outside the versioned API. Kept out of the
        // published documentation so /docs describes the API a client is meant to call.
        get("/") {
            call.respondText("Hello, World!")
        }.hide()
        get("/json/kotlinx-serialization") {
            call.respond(mapOf("hello" to "world"))
        }.hide()
    }
}

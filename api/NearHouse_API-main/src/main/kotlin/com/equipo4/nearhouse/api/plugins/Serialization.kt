package com.equipo4.nearhouse.api.plugins

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json

/** Permite recibir y responder JSON convirtiendo automáticamente a clases de Kotlin. */
fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true // si la app manda campos de más, no truena
                encodeDefaults = true
            },
        )
    }
}

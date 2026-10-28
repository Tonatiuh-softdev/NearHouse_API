package com.equipo4.nearhouse.api.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import org.slf4j.event.Level

/** Escribe en consola cada petición que llega (método, ruta y código de respuesta). */
fun Application.configureMonitoring() {
    install(CallLogging) {
        level = Level.INFO
    }
}

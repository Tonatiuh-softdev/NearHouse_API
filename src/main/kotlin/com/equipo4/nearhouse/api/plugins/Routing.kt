package com.equipo4.nearhouse.api.plugins

import com.equipo4.nearhouse.api.usuarios.UsuarioService
import com.equipo4.nearhouse.api.usuarios.usuarioRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import javax.sql.DataSource

@Serializable
data class InfoResponse(val nombre: String, val version: String)

@Serializable
data class HealthResponse(val estado: String, val baseDeDatos: String)

/** Registra todas las rutas de la API. */
fun Application.configureRouting(dataSource: DataSource, usuarioService: UsuarioService) {
    routing {
        get("/") {
            call.respond(InfoResponse(nombre = "NearHouse API", version = "1.0.0"))
        }

        // Para revisar rápido si la API y la base de datos están vivas
        get("/health") {
            val bdOk = withContext(Dispatchers.IO) {
                try {
                    dataSource.connection.use { it.isValid(2) }
                } catch (e: Exception) {
                    false
                }
            }
            if (bdOk) {
                call.respond(HealthResponse(estado = "ok", baseDeDatos = "conectada"))
            } else {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    HealthResponse(estado = "error", baseDeDatos = "sin conexión"),
                )
            }
        }

        // Todas las rutas de negocio (incluidas /auth/google y /auth/register/google) viven bajo /api/v1
        route("/api/v1") {
            usuarioRoutes(usuarioService)
        }
    }
}
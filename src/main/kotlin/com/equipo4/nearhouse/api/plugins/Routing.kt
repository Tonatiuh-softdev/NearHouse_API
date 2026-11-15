package com.equipo4.nearhouse.api.plugins

import com.equipo4.nearhouse.api.admin.usuarios.AdminUsuarioService
import com.equipo4.nearhouse.api.admin.usuarios.adminUsuarioRoutes
import com.equipo4.nearhouse.api.apelaciones.ApelacionService
import com.equipo4.nearhouse.api.apelaciones.apelacionRoutes
import com.equipo4.nearhouse.api.colonias.ColoniaService
import com.equipo4.nearhouse.api.colonias.coloniaRoutes
import com.equipo4.nearhouse.api.guardados.GuardadoService
import com.equipo4.nearhouse.api.guardados.guardadoRoutes
import com.equipo4.nearhouse.api.notificaciones.DispositivoService
import com.equipo4.nearhouse.api.notificaciones.NotificacionService
import com.equipo4.nearhouse.api.notificaciones.notificacionRoutes
import com.equipo4.nearhouse.api.publicaciones.PublicacionService
import com.equipo4.nearhouse.api.publicaciones.publicacionRoutes
import com.equipo4.nearhouse.api.reportes.ReporteService
import com.equipo4.nearhouse.api.reportes.reporteRoutes
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
import com.equipo4.nearhouse.api.publicaciones.compartirRoutes

@Serializable
data class InfoResponse(val nombre: String, val version: String)

@Serializable
data class HealthResponse(val estado: String, val baseDeDatos: String)

/** Registra todas las rutas de la API. */
fun Application.configureRouting(
    dataSource: DataSource,
    usuarioService: UsuarioService,
    coloniaService: ColoniaService,
    publicacionService: PublicacionService,
    reporteService: ReporteService,
    adminUsuarioService: AdminUsuarioService,
    notificacionService: NotificacionService,
    dispositivoService: DispositivoService,
    guardadoService: GuardadoService,
    apelacionService: ApelacionService,
) {
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

        // Página pública para compartir un anuncio: /p/{id}
        compartirRoutes(publicacionService)

        // Todas las rutas de negocio viven bajo /api/v1
        route("/api/v1") {
            usuarioRoutes(usuarioService)
            coloniaRoutes(coloniaService)
            publicacionRoutes(publicacionService)
            reporteRoutes(reporteService)
            adminUsuarioRoutes(adminUsuarioService)
            notificacionRoutes(notificacionService)
            guardadoRoutes(guardadoService)
            apelacionRoutes(apelacionService)
        }
    }
}

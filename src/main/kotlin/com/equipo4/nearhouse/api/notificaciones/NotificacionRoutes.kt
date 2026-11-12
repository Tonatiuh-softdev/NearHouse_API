package com.equipo4.nearhouse.api.notificaciones

import com.equipo4.nearhouse.api.auth.JwtService
import com.equipo4.nearhouse.api.common.ValidacionException
import com.equipo4.nearhouse.api.common.usuarioId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.route

/** Notificaciones del usuario con sesión. Cada usuario solo ve, marca o borra las suyas. */
fun Route.notificacionRoutes(service: NotificacionService) {
    authenticate(JwtService.AUTH_NAME) {
        route("/notificaciones") {

            // GET /api/v1/notificaciones?pagina=1
            get {
                val pagina = call.request.queryParameters["pagina"]?.toIntOrNull() ?: 1
                call.respond(service.listar(call.usuarioId(), pagina))
            }

            // GET /api/v1/notificaciones/no-leidas -> para el contador de la campana
            get("/no-leidas") {
                call.respond(service.noLeidas(call.usuarioId()))
            }

            // PATCH /api/v1/notificaciones/leidas -> marca todas como leídas
            patch("/leidas") {
                service.marcarTodasLeidas(call.usuarioId())
                call.respond(HttpStatusCode.NoContent)
            }

            // PATCH /api/v1/notificaciones/{id}/leida
            patch("/{id}/leida") {
                service.marcarLeida(call.usuarioId(), call.idDeRuta())
                call.respond(HttpStatusCode.NoContent)
            }

            // DELETE /api/v1/notificaciones -> borra todas
            delete {
                service.borrarTodas(call.usuarioId())
                call.respond(HttpStatusCode.NoContent)
            }

            // DELETE /api/v1/notificaciones/{id}
            delete("/{id}") {
                service.borrar(call.usuarioId(), call.idDeRuta())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun ApplicationCall.idDeRuta(): Int =
    parameters["id"]?.toIntOrNull() ?: throw ValidacionException(listOf("El id no es válido"))
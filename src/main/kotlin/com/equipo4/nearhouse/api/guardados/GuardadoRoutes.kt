package com.equipo4.nearhouse.api.guardados

import com.equipo4.nearhouse.api.auth.JwtService
import com.equipo4.nearhouse.api.common.ValidacionException
import com.equipo4.nearhouse.api.common.usuarioId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.guardadoRoutes(service: GuardadoService) {
    authenticate(JwtService.AUTH_NAME) {
        route("/guardados") {

            // GET /api/v1/guardados?pagina=1
            get {
                val pagina = call.request.queryParameters["pagina"]?.toIntOrNull() ?: 1
                val respuesta = service.listar(call.usuarioId(), pagina)
                call.respond(HttpStatusCode.OK, respuesta)
            }

            // POST /api/v1/guardados/{idPublicacion}
            post("/{idPublicacion}") {
                val idPublicacion = call.parameters["idPublicacion"]?.toIntOrNull()
                    ?: throw ValidacionException(listOf("El ID de la publicación debe ser numérico"))
                service.guardar(call.usuarioId(), idPublicacion)
                call.respond(HttpStatusCode.OK)
            }

            // DELETE /api/v1/guardados/{idPublicacion}
            delete("/{idPublicacion}") {
                val idPublicacion = call.parameters["idPublicacion"]?.toIntOrNull()
                    ?: throw ValidacionException(listOf("El ID de la publicación debe ser numérico"))
                service.eliminar(call.usuarioId(), idPublicacion)
                call.respond(HttpStatusCode.OK)
            }
        }
    }
}

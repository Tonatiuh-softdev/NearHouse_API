package com.equipo4.nearhouse.api.notificaciones

import com.equipo4.nearhouse.api.auth.JwtService
import com.equipo4.nearhouse.api.common.recibir
import com.equipo4.nearhouse.api.common.usuarioId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.dispositivoRoutes(service: DispositivoService) {
    authenticate(JwtService.AUTH_NAME) {
        // POST /api/v1/dispositivos  {"token": "...", "idioma": "es"}
        post("/dispositivos") {
            service.registrar(call.usuarioId(), call.recibir<RegistrarDispositivoRequest>())
            call.respond(HttpStatusCode.NoContent)
        }

        // POST /api/v1/dispositivos/baja  {"token": "..."} (al cerrar sesión)
        post("/dispositivos/baja") {
            service.baja(call.usuarioId(), call.recibir<BajaDispositivoRequest>())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
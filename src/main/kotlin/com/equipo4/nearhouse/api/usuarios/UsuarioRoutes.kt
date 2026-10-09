package com.equipo4.nearhouse.api.usuarios

import com.equipo4.nearhouse.api.auth.JwtService
import com.equipo4.nearhouse.api.common.recibir
import com.equipo4.nearhouse.api.common.usuarioId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Rutas de usuarios. Solo traducen HTTP <-> servicio:
 * leen el JSON, llaman al servicio y responden.
 */
fun Route.usuarioRoutes(service: UsuarioService) {

    route("/auth") {
        // POST /api/v1/auth/registro -> crea una cuenta de Comprador
        post("/registro") {
            val req = call.recibir<RegistroRequest>()
            call.respond(HttpStatusCode.Created, service.registrar(req))
        }

        // POST /api/v1/auth/login -> regresa el token y los datos del usuario
        post("/login") {
            val req = call.recibir<LoginRequest>()
            call.respond(service.iniciarSesion(req))
        }
    }

    // Rutas que exigen token: Authorization: Bearer <token>
    authenticate(JwtService.AUTH_NAME) {
        // GET /api/v1/usuarios/me -> datos del usuario que inició sesión
        get("/usuarios/me") {
            call.respond(service.obtenerPerfil(call.usuarioId()))
        }
    }
}

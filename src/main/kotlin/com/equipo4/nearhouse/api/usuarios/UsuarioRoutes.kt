package com.equipo4.nearhouse.api.usuarios

import com.equipo4.nearhouse.api.auth.GoogleAuthRequest
import com.equipo4.nearhouse.api.auth.GoogleRegisterRequest
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

        // 1. POST /api/v1/auth/solicitar-codigo-correo -> Genera y envía código de 6 dígitos
        post("/solicitar-codigo-correo") {
            val req = call.recibir<RequestCodigoCorreo>()
            service.solicitarCodigoCorreo(req.correo)
            call.respond(HttpStatusCode.OK)
        }

        // 2. POST /api/v1/auth/verificar-codigo-correo -> Valida el código y regresa comprobanteCorreo (JWT)
        post("/verificar-codigo-correo") {
            val req = call.recibir<VerifyCodigoCorreoRequest>()
            val comprobante = service.verificarCodigoCorreo(req.correo, req.codigo)
            call.respond(HttpStatusCode.OK, VerifyCodigoCorreoResponse(comprobanteCorreo = comprobante))
        }

        // 3. POST /api/v1/auth/check-telefono -> Revisa que el teléfono no esté registrado en la BD
        post("/check-telefono") {
            val req = call.recibir<CheckTelefonoRequest>()
            service.checkTelefonoDisponible(req.telefono)
            call.respond(HttpStatusCode.OK)
        }

        // 4. POST /api/v1/auth/registro -> Crea la cuenta con comprobante de correo + token de Firebase
        post("/registro") {
            val req = call.recibir<RegistroFinalRequest>()
            call.respond(HttpStatusCode.Created, service.registrar(req))
        }

        // 5. POST /api/v1/auth/login -> regresa el token y los datos del usuario
        post("/login") {
            val req = call.recibir<LoginRequest>()
            call.respond(service.iniciarSesion(req))
        }

        // 6. POST /api/v1/auth/google -> Entrada o inicio de registro con Google (un solo endpoint)
        post("/google") {
            val req = call.recibir<GoogleAuthRequest>()
            val respuesta = service.autenticarGoogle(req.idToken)
            call.respond(HttpStatusCode.OK, respuesta)
        }

        // 7. POST /api/v1/auth/register/google -> Registro final tras validar comprobante y SMS
        post("/register/google") {
            val req = call.recibir<GoogleRegisterRequest>()
            val respuesta = service.registrarGoogleFinal(req)
            call.respond(HttpStatusCode.Created, respuesta)
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
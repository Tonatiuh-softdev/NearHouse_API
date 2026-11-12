package com.equipo4.nearhouse.api.usuarios

import com.equipo4.nearhouse.api.auth.JwtService
import com.equipo4.nearhouse.api.common.ValidacionException
import com.equipo4.nearhouse.api.common.recibir
import com.equipo4.nearhouse.api.common.usuarioId
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import com.equipo4.nearhouse.api.common.NoEncontradoException
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header

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
            val respuesta = service.autenticarGoogle(req.idToken, req.reactivar)
            call.respond(HttpStatusCode.OK, respuesta)
        }

        // 7. POST /api/v1/auth/register/google -> Registro final tras validar comprobante y SMS
        post("/register/google") {
            val req = call.recibir<GoogleRegisterRequest>()
            val respuesta = service.registrarGoogleFinal(req)
            call.respond(HttpStatusCode.Created, respuesta)
        }

        // 8. POST /api/v1/auth/recuperar/solicitar-codigo -> Solicita código para recuperar contraseña
        post("/recuperar/solicitar-codigo") {
            val req = call.recibir<SolicitarCodigoRecuperarRequest>()
            service.solicitarCodigoRecuperar(req.correo, call.application)
            call.respond(HttpStatusCode.OK, SolicitarCodigoRecuperarResponse())
        }

        // 9. POST /api/v1/auth/recuperar/verificar -> Verifica código y regresa comprobante
        post("/recuperar/verificar") {
            val req = call.recibir<VerificarCodigoRecuperarRequest>()
            val comprobante = service.verificarCodigoRecuperar(req.correo, req.codigo)
            call.respond(HttpStatusCode.OK, VerificarCodigoRecuperarResponse(comprobante))
        }

        // 10. POST /api/v1/auth/recuperar/nueva-contrasena -> Guarda la nueva contraseña sin token de sesión
        post("/recuperar/nueva-contrasena") {
            val req = call.recibir<NuevaContrasenaRecuperarRequest>()
            service.guardarNuevaContrasenaRecuperar(req)
            call.respond(HttpStatusCode.OK)
        }
    }

    // GET /api/v1/planes -> público, planes activos del más barato al más caro
    get("/planes") {
        call.respond(service.listarPlanes())
    }

    // Rutas que exigen token: Authorization: Bearer <token>
    authenticate(JwtService.AUTH_NAME) {

        // GET /api/v1/usuarios/me -> datos del usuario que inició sesión
        get("/usuarios/me") {
            call.respond(service.obtenerPerfil(call.usuarioId()))
        }

        // PATCH /api/v1/usuarios/me -> cambia nombre y apellidos (correo, teléfono y rol se ignoran)
        patch("/usuarios/me") {
            val req = call.recibir<ActualizarPerfilRequest>()
            call.respond(service.actualizarPerfil(call.usuarioId(), req))
        }

        // POST /api/v1/usuarios/me/desactivar -> desactiva la cuenta del usuario
        post("/usuarios/me/desactivar") {
            val req = call.recibir<DesactivarCuentaRequest>()
            service.desactivarCuenta(call.usuarioId(), req)
            call.respond(HttpStatusCode.NoContent)
        }

        // POST /api/v1/usuarios/me/contrasena/codigo -> Solicita código para cambiar/crear contraseña
        post("/usuarios/me/contrasena/codigo") {
            val respuesta = service.solicitarCodigoContrasena(call.usuarioId())
            call.respond(HttpStatusCode.OK, respuesta)
        }

        // POST /api/v1/usuarios/me/contrasena/verificar -> Valida código y regresa comprobante JWT
        post("/usuarios/me/contrasena/verificar") {
            val req = call.recibir<VerificarCodigoContrasenaRequest>()
            val comprobante = service.verificarCodigoContrasena(call.usuarioId(), req.codigo)
            call.respond(HttpStatusCode.OK, VerificarCodigoContrasenaResponse(comprobante))
        }

        // PUT /api/v1/usuarios/me/contrasena -> Cambia o crea la contraseña
        put("/usuarios/me/contrasena") {
            val req = call.recibir<CambiarContrasenaRequest>()
            val respuesta = service.cambiarOcrearContrasena(call.usuarioId(), req)
            call.respond(HttpStatusCode.OK, respuesta)
        }

        // POST /api/v1/usuarios/me/correo/solicitar-codigo -> Solicita código para cambiar correo
        post("/usuarios/me/correo/solicitar-codigo") {
            val req = call.recibir<SolicitarCodigoCambioCorreoRequest>()
            service.solicitarCodigoCambioCorreo(call.usuarioId(), req)
            call.respond(HttpStatusCode.OK)
        }

        // POST /api/v1/usuarios/me/correo/confirmar -> Confirma cambio de correo
        post("/usuarios/me/correo/confirmar") {
            val req = call.recibir<ConfirmarCambioCorreoRequest>()
            val respuesta = service.confirmarCambioCorreo(call.usuarioId(), req)
            call.respond(HttpStatusCode.OK, respuesta)
        }

        // PUT /api/v1/usuarios/me/foto -> sube la foto (multipart, campo "foto")
        put("/usuarios/me/foto") {
            val bytes = call.recibirFoto()
            call.respond(service.guardarFoto(call.usuarioId(), bytes))
        }

        // DELETE /api/v1/usuarios/me/foto -> borra la foto
        delete("/usuarios/me/foto") {
            call.respond(service.eliminarFoto(call.usuarioId()))
        }

        // GET /api/v1/usuarios/me/foto -> regresa la imagen con su tipo (404 si no tiene)
        get("/usuarios/me/foto") {
            val foto = service.obtenerFoto(call.usuarioId())
            call.respondBytes(foto.bytes, ContentType.parse(foto.tipo))
        }

        // GET /api/v1/usuarios/{id}/foto -> foto pública de cualquier usuario (404 si no tiene)
        get("/usuarios/{id}/foto") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: throw NoEncontradoException("La foto no existe")
            val foto = service.obtenerFoto(id)
            // El parámetro ?v= cambia cuando el usuario sube otra foto, así que se puede cachear
            call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
            call.respondBytes(foto.bytes, ContentType.parse(foto.tipo))
        }

        // POST /api/v1/usuarios/me/plan -> contrata o cambia de plan; regresa token nuevo
        post("/usuarios/me/plan") {
            val req = call.recibir<ContratarPlanRequest>()
            call.respond(service.contratarPlan(call.usuarioId(), req))
        }
    }
}

/** Lee el archivo del campo "foto" de un formulario multipart. */
private suspend fun ApplicationCall.recibirFoto(): ByteArray {
    val multipart = receiveMultipart()
    var bytes: ByteArray? = null
    multipart.forEachPart { part ->
        if (part is PartData.FileItem && part.name == "foto" && bytes == null) {
            bytes = part.provider().readRemaining().readByteArray()
        }
        part.dispose()
    }
    return bytes ?: throw ValidacionException(listOf("Falta el archivo en el campo 'foto'"))
}

package com.equipo4.nearhouse.api.auth

import com.equipo4.nearhouse.api.usuarios.UsuarioRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class GoogleAuthRequest(val idToken: String? = null)

@Serializable
data class GoogleRegisterRequest(
    val nombre: String,
    val apellidos: String,
    val telefono: String,
    val googleProofToken: String,
    val smsToken: String
)

fun Route.googleAuthRouting(
    verifier: FirebaseTokenVerifier,
    proofService: GoogleProofService,
    jwtService: JwtService,
    usuarioRepository: UsuarioRepository
) {
    // PARTE A: ENTRAR O REGISTRARSE
    post("/api/v1/auth/google") {
        val request = runCatching { call.receive<GoogleAuthRequest>() }.getOrNull()

        // Prueba 3: Peticion sin token -> 400
        if (request?.idToken.isNullOrBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Token de Google es requerido"))
        }

        // Pruebas 1 y 2: Token inventado o firma alterada -> 401
        val firebasePayload = verifier.verifyToken(request.idToken!!)
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token de Google inválido o no verificado"))

        // CASO A: Ya tiene cuenta ligada a Google
        val userByGoogle = usuarioRepository.findByGoogleId(firebasePayload.googleId)
        if (userByGoogle != null) {
            if (!userByGoogle.b_estatus_usuario) {
                return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Cuenta suspendida"))
            }
            val token = jwtService.generateToken(userByGoogle)
            return@post call.respond(HttpStatusCode.OK, mapOf("token" to token, "usuario" to userByGoogle))
        }

        // CASO B: No ligada, pero existe correo -> Ligar Google ID
        val userByEmail = usuarioRepository.findByEmail(firebasePayload.email)
        if (userByEmail != null) {
            usuarioRepository.updateGoogleId(userByEmail.pke_id_usuario, firebasePayload.googleId)
            val token = jwtService.generateToken(userByEmail)
            return@post call.respond(HttpStatusCode.OK, mapOf("token" to token, "usuario" to userByEmail))
        }

        // CASO C: Usuario nuevo -> Enviar comprobante de 30 mins
        val proofToken = proofService.createProofToken(firebasePayload)
        return@post call.respond(HttpStatusCode.OK, mapOf(
            "requiresRegistration" to true,
            "googleProofToken" to proofToken,
            "nombre" to firebasePayload.givenName,
            "apellidos" to firebasePayload.familyName,
            "correo" to firebasePayload.email
        ))
    }

    // PARTE B: REGISTRO FINAL CON GOOGLE
    post("/api/v1/auth/register/google") {
        val request = call.receive<GoogleRegisterRequest>()

        // Pruebas 6, 7 y 8: Comprobante vencido, alterado o token de sesion normal
        val proofData = proofService.verifyProofToken(request.googleProofToken)
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Comprobante de Google inválido o expirado"))

        // Prueba 9: Token SMS vacio/invalido (Integrar con tu verificador existente)
        if (request.smsToken.isBlank()) {
            return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token SMS requerido"))
        }

        // Prueba 10: Datos repetidos -> 409 Conflict
        if (usuarioRepository.existsByEmail(proofData.email) ||
            usuarioRepository.existsByPhone(request.telefono) ||
            usuarioRepository.existsByGoogleId(proofData.googleId)) {
            return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "El correo, teléfono o Google ID ya está registrado"))
        }

        // Crear cuenta sin contraseña
        val newUser = usuarioRepository.createUserWithoutPassword(
            nombre = request.nombre,
            apellidos = request.apellidos,
            telefono = request.telefono,
            email = proofData.email,
            googleId = proofData.googleId
        )

        val token = jwtService.generateToken(newUser)
        call.respond(HttpStatusCode.Created, mapOf("token" to token, "usuario" to newUser))
    }
}
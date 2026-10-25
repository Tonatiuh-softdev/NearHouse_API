package com.equipo4.nearhouse.api.usuarios

import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

// ---------- Lo que la app MANDA ----------

@Serializable
data class GoogleAuthRequest(
    val idToken: String? = null
)

@Serializable
data class GoogleRegisterRequest(
    val nombre: String,
    val apellidos: String,
    val telefono: String,
    val googleProofToken: String,
    val smsToken: String
)

@Serializable
data class RequestCodigoCorreo(
    val correo: String
)

@Serializable
data class VerifyCodigoCorreoRequest(
    val correo: String,
    val codigo: String
)

@Serializable
data class CheckTelefonoRequest(
    val telefono: String
)

@Serializable
data class RegistroFinalRequest(
    val correo: String,
    val contrasena: String,
    val nombre: String,
    val primerApellido: String,
    val segundoApellido: String? = null,
    val telefono: String,
    val comprobanteCorreo: String,
    val tokenFirebase: String
)

@Serializable
data class RegistroRequest(
    val correo: String,
    val contrasena: String,
    val nombre: String,
    val primerApellido: String,
    val segundoApellido: String? = null,
    val telefono: String, // con código de país, por ejemplo "+523141234567"
)

@Serializable
data class LoginRequest(
    val correo: String,
    val contrasena: String,
)

/** PATCH /usuarios/me: solo se pueden cambiar nombre y apellidos. */
@Serializable
data class ActualizarPerfilRequest(
    val nombre: String,
    val primerApellido: String,
    val segundoApellido: String? = null
)

// ---------- Lo que la API RESPONDE ----------

@Serializable
data class VerifyCodigoCorreoResponse(
    val comprobanteCorreo: String
)

@Serializable
data class UsuarioResponse(
    val id: Int,
    val nombre: String,
    val primerApellido: String,
    val segundoApellido: String?,
    val correo: String,
    val telefono: String,
    val rol: String,
    val activo: Boolean,
    val correoVerificado: Boolean,
    val telefonoVerificado: Boolean,
    val fechaRegistro: String,
    val tieneFoto: Boolean = false,
    // Milisegundos de la fecha de la foto. Sirve para no usar la foto vieja en caché.
    val fotoVersion: String? = null
)

@Serializable
data class AuthResponse(
    val token: String,
    val usuario: UsuarioResponse,
)

// ---------- Modelos internos (nunca se mandan a la app) ----------

/** Un usuario tal como está en la base de datos. La contraseña es nula si se registró con Google. */
data class Usuario(
    val id: Int,
    val nombre: String,
    val primerApellido: String,
    val segundoApellido: String?,
    val correo: String,
    val telefono: String,
    val contrasenaHash: String?,
    val googleId: String?,
    val rol: String,
    val activo: Boolean,
    val correoVerificado: Boolean,
    val telefonoVerificado: Boolean,
    val fechaRegistro: String,
    val tieneFoto: Boolean = false,
    val fechaFoto: OffsetDateTime? = null,
) {
    /** Versión segura para responder: sin el hash de la contraseña ni datos sensibles. */
    fun toResponse() = UsuarioResponse(
        id = id,
        nombre = nombre,
        primerApellido = primerApellido,
        segundoApellido = segundoApellido,
        correo = correo,
        telefono = telefono,
        rol = rol,
        activo = activo,
        correoVerificado = correoVerificado,
        telefonoVerificado = telefonoVerificado,
        fechaRegistro = fechaRegistro,
        tieneFoto = tieneFoto,
        fotoVersion = if (tieneFoto) fechaFoto?.toInstant()?.toEpochMilli()?.toString() else null,
    )
}

/** Foto de perfil tal como está guardada en la base de datos. */
class FotoPerfil(val bytes: ByteArray, val tipo: String)

/** Datos ya validados y limpios, listos para insertarse. */
data class NuevoUsuario(
    val nombre: String,
    val primerApellido: String,
    val segundoApellido: String? = null,
    val correo: String,
    val telefono: String,
    val contrasenaHash: String? = null,
    val googleId: String? = null,
    val rol: String,
    val correoVerificado: Boolean = false,
    val telefonoVerificado: Boolean = false
)
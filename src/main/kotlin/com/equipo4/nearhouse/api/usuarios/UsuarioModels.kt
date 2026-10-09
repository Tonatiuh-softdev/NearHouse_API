package com.equipo4.nearhouse.api.usuarios

import kotlinx.serialization.Serializable

// ---------- Lo que la app MANDA ----------

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

// ---------- Lo que la API RESPONDE ----------

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
)

@Serializable
data class AuthResponse(
    val token: String,
    val usuario: UsuarioResponse,
)

// ---------- Modelos internos (nunca se mandan a la app) ----------

/** Un usuario tal como está en la base de datos, incluido el hash de su contraseña. */
data class Usuario(
    val id: Int,
    val nombre: String,
    val primerApellido: String,
    val segundoApellido: String?,
    val correo: String,
    val telefono: String,
    val contrasenaHash: String,
    val rol: String,
    val activo: Boolean,
    val correoVerificado: Boolean,
    val telefonoVerificado: Boolean,
    val fechaRegistro: String,
) {
    /** Versión segura para responder: sin el hash de la contraseña. */
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
    )
}

/** Datos ya validados y limpios, listos para insertarse. */
data class NuevoUsuario(
    val nombre: String,
    val primerApellido: String,
    val segundoApellido: String?,
    val correo: String,
    val telefono: String,
    val contrasenaHash: String,
    val rol: String,
)

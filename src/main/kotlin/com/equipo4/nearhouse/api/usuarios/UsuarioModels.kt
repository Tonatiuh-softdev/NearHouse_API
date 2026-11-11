package com.equipo4.nearhouse.api.usuarios

import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

// ---------- Lo que la app MANDA ----------

@Serializable
data class GoogleAuthRequest(
    val idToken: String? = null,
    val reactivar: Boolean = false
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
    val reactivar: Boolean = false
)

@Serializable
data class DesactivarCuentaRequest(
    val contrasena: String? = null,
    val googleToken: String? = null
)

@Serializable
data class VerificarCodigoContrasenaRequest(
    val codigo: String
)

@Serializable
data class CambiarContrasenaRequest(
    val comprobante: String,
    val contrasenaActual: String? = null,
    val contrasenaNueva: String
)

@Serializable
data class SolicitarCodigoCambioCorreoRequest(
    val correoNuevo: String,
    val contrasena: String? = null,
    val googleToken: String? = null
)

@Serializable
data class ConfirmarCambioCorreoRequest(
    val correoNuevo: String,
    val codigo: String
)

@Serializable
data class SolicitarCodigoRecuperarRequest(
    val correo: String
)

@Serializable
data class SolicitarCodigoRecuperarResponse(
    val mensaje: String = "Si el correo está registrado, te enviamos un código"
)

@Serializable
data class VerificarCodigoRecuperarRequest(
    val correo: String,
    val codigo: String
)

@Serializable
data class VerificarCodigoRecuperarResponse(
    val comprobante: String
)

@Serializable
data class NuevaContrasenaRecuperarRequest(
    val comprobante: String,
    val contrasenaNueva: String
)

@Serializable
data class ValidarCambioTelefonoRequest(
    val telefonoNuevo: String,
    val contrasena: String? = null,
    val googleToken: String? = null
)

@Serializable
data class ValidarCambioTelefonoResponse(
    val comprobante: String
)

@Serializable
data class CambiarTelefonoRequest(
    val comprobante: String,
    val tokenFirebase: String
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
data class CodigoContrasenaResponse(
    val correoOculto: String,
    val tieneContrasena: Boolean
)

@Serializable
data class VerificarCodigoContrasenaResponse(
    val comprobante: String
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
    val fotoVersion: String? = null,
    val plan: PlanUsuarioResponse? = null,
    val pruebaDisponible: Boolean = false,
    val desactivada: Boolean = false,
    val tieneContrasena: Boolean = false
)

@Serializable
data class AuthResponse(
    val token: String,
    val usuario: UsuarioResponse,
)

@Serializable
data class ContratarPlanRequest(
    val planId: Int
)

@Serializable
data class PlanResponse(
    val id: Int,
    val nombre: String,
    val precio: Int,
    val duracionDias: Int,
    val limiteAnuncios: Int? = null,
    val nivelVisibilidad: Int,
    val diasPrueba: Int,
    val destacado: Boolean
)

@Serializable
data class PlanUsuarioResponse(
    val id: Int,
    val nombre: String,
    val fechaInicio: String,
    val fechaVencimiento: String,
    val vigente: Boolean
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
    val planId: Int? = null,
    val planNombre: String? = null,
    val planInicio: OffsetDateTime? = null,
    val planVencimiento: OffsetDateTime? = null,
    val pruebaUsada: Boolean = false,
    val fechaDesactivacion: OffsetDateTime? = null,
    val fechaEliminacionProgramada: OffsetDateTime? = null,
    val versionSesion: Int = 1,
    val intentosFallidos: Int = 0,
    val bloqueoHasta: OffsetDateTime? = null
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
        plan = planId?.let { pid ->
            val inicio = planInicio
            val venc = planVencimiento
            if (inicio != null && venc != null) {
                PlanUsuarioResponse(
                    id = pid,
                    nombre = planNombre.orEmpty(),
                    fechaInicio = inicio.toString(),
                    fechaVencimiento = venc.toString(),
                    vigente = venc.isAfter(OffsetDateTime.now())
                )
            } else {
                null
            }
        },
        pruebaDisponible = !pruebaUsada,
        desactivada = fechaDesactivacion != null,
        tieneContrasena = contrasenaHash != null
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

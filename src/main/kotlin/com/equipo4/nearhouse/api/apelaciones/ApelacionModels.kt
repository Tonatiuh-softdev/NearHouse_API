package com.equipo4.nearhouse.api.apelaciones

import com.equipo4.nearhouse.api.common.ApiException
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/**
 * Datos de la apelación. Viajan en el campo "datos" del multipart; las imágenes van en "fotos".
 * [token] solo se usa al apelar una suspensión: lo da el inicio de sesión (la cuenta no tiene sesión).
 */
@Serializable
data class CrearApelacionRequest(
    val descripcion: String,
    val token: String? = null
)

@Serializable
data class ApelacionCreadaResponse(
    val id: Int,
    val estado: String       // siempre PENDIENTE al crear
)

/** Quien apeló, como lo ve el administrador. */
@Serializable
data class ApelanteResponse(
    val id: Int,
    val nombre: String,
    val correo: String,
    val tieneFoto: Boolean
)

/** La publicación de una apelación de tipo PUBLICACION. */
@Serializable
data class ApelacionPublicacionResponse(
    val id: Int,
    val titulo: String,
    val fotoId: Int?,
    val estado: String
)

/** [tipo]: PUBLICACION o SUSPENSION. [publicacion] solo viene en PUBLICACION. */
@Serializable
data class ApelacionListaResponse(
    val id: Int,
    val tipo: String,
    val descripcionCorta: String,
    val fecha: Long,
    val estado: String,
    val imagenes: Int,
    val apelante: ApelanteResponse,
    val publicacion: ApelacionPublicacionResponse?
)

@Serializable
data class ApelacionDetalleResponse(
    val id: Int,
    val tipo: String,
    val descripcion: String,
    val fecha: Long,
    val estado: String,
    val imagenes: List<Int>,
    val apelante: ApelanteResponse,
    val publicacion: ApelacionPublicacionResponse?,
    /** La medida apelada: motivo que dio el administrador, cuándo y quién la tomó. */
    val motivoMedida: String,
    val fechaMedida: Long,
    val medidaPorId: Int?,
    val medidaPorNombre: String?,
    /** Solo en SUSPENSION: hasta cuándo (null = indefinida o ya terminó; ver [suspensionVigente]). */
    val suspendidaHasta: Long?,
    val suspensionVigente: Boolean,
    /** Resolución: respuesta del administrador (la ve quien apeló). */
    val nota: String?,
    val resueltoPor: String?,
    val fechaResolucion: Long?
)

@Serializable
data class ResolverApelacionRequest(
    val estado: String,      // ACEPTADA (se revierte la medida) o RECHAZADA (se mantiene)
    val nota: String
)

/** Estado de la apelación de un anuncio retirado, para "Mis anuncios". */
data class ApelacionResumen(val estado: String?, val nota: String?)

/** Anuncio retirado por un reporte que todavía se puede apelar. */
data class MedidaPublicacion(
    val titulo: String,
    val fecha: OffsetDateTime,
    val motivo: String,
    val adminId: Int?
)

/** Suspensión vigente de una cuenta. [hasta] null = indefinida. */
data class SuspensionVigente(
    val desde: OffsetDateTime,
    val hasta: OffsetDateTime?,
    val motivo: String,
    val adminId: Int?
)

/** Lo que hace falta para avisar a quien apeló cuando se resuelve. */
data class AvisoApelacion(
    val apelanteId: Int,
    val correo: String,
    val tipo: String,
    val publicacionId: Int?,
    val titulo: String
)

/** Cuerpo del 403 cuando la cuenta está suspendida (inicio de sesión con correo o con Google). */
@Serializable
data class CuentaSuspendidaResponse(
    val error: String,
    val detalles: List<String> = emptyList(),
    val codigo: String,
    val motivo: String,
    /** Fecha ISO hasta la que dura. Null = indefinida. */
    val hasta: String?,
    /** Para mandar la apelación sin sesión (dura 30 minutos). Null si ya apeló esta suspensión. */
    val apelacionToken: String?,
    /** Si ya apeló esta suspensión: PENDIENTE o RECHAZADA. */
    val apelacionEstado: String?,
    val apelacionNota: String?
)

/** 403 de cuenta suspendida, con lo necesario para apelar. Lo convierte StatusPages. */
class CuentaSuspendidaException(
    val motivo: String,
    val hasta: String?,
    val apelacionToken: String?,
    val apelacionEstado: String?,
    val apelacionNota: String?
) : ApiException(
    HttpStatusCode.Forbidden,
    "Tu cuenta está suspendida. Motivo: $motivo",
    codigo = "CUENTA_SUSPENDIDA"
) {
    fun toResponse() = CuentaSuspendidaResponse(
        error = message,
        codigo = codigo ?: "CUENTA_SUSPENDIDA",
        motivo = motivo,
        hasta = hasta,
        apelacionToken = apelacionToken,
        apelacionEstado = apelacionEstado,
        apelacionNota = apelacionNota
    )
}

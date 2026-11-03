package com.equipo4.nearhouse.api.reportes

import kotlinx.serialization.Serializable

/** Datos del reporte. Viajan en el campo "datos" del multipart; las imágenes van en "fotos". */
@Serializable
data class CrearReporteRequest(
    val categoria: String,   // INFORMACION_FALSA, CONTENIDO_OFENSIVO o POSIBLE_FRAUDE
    val motivo: String,      // código del motivo (por ejemplo PRECIO_FALSO)
    val descripcion: String
)

@Serializable
data class ReporteCreadoResponse(
    val id: Int,
    val estado: String       // siempre PENDIENTE al crear
)

/** Publicación visible para reportar: solo lo necesario para validar el dueño. */
data class PublicacionVisible(val vendedorId: Int)

class FotoEvidencia(val bytes: ByteArray, val tipo: String)

/** Motivos válidos por categoría. Debe coincidir con ck_reporte_motivo de la migración 10. */
val MOTIVOS_POR_CATEGORIA: Map<String, Set<String>> = mapOf(
    "INFORMACION_FALSA" to setOf(
        "FOTOS_NO_CORRESPONDEN", "PRECIO_FALSO", "DATOS_INCORRECTOS",
        "UBICACION_INCORRECTA", "PROPIEDAD_NO_EXISTE"
    ),
    "CONTENIDO_OFENSIVO" to setOf(
        "LENGUAJE_OFENSIVO", "CONTENIDO_DISCRIMINATORIO", "CONTENIDO_SEXUAL",
        "CONTENIDO_VIOLENTO", "OTRO"
    ),
    "POSIBLE_FRAUDE" to setOf(
        "PRECIO_FALSO", "DATOS_INCORRECTOS", "PUBLICADA_POR_VARIOS",
        "YA_NO_DISPONIBLE", "OTRO"
    )
)
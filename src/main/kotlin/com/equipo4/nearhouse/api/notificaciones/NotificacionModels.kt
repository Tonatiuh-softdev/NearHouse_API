package com.equipo4.nearhouse.api.notificaciones

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Tipos de aviso. Deben coincidir con la regla ck_notificacion_tipo (migraciones 12 y 16). */
enum class TipoNotificacion {
    BIENVENIDA,
    PLAN_CONTRATADO,
    PUBLICACION_EN_REVISION,
    PUBLICACION_APROBADA,
    PUBLICACION_RECHAZADA,
    PUBLICACION_RETIRADA,
    REPORTE_ENVIADO,
    REPORTE_ATENDIDO,
    REPORTE_DESCARTADO,
    // Migración 16: apelaciones
    APELACION_ENVIADA,
    APELACION_ACEPTADA,
    APELACION_RECHAZADA
}

/** Un aviso tal como lo ve la app. El texto no viene: la app lo arma con sus strings.xml. */
@Serializable
data class NotificacionResponse(
    val id: Int,
    val tipo: TipoNotificacion,
    val datos: JsonElement,
    val leida: Boolean,
    /** Fecha de registro en milisegundos (UTC). La app la formatea. */
    val fecha: Long,
    val idPublicacion: Int?,
    val idReporte: Int?,
    /** Id de la primera foto de la publicación, si todavía existe. Para la miniatura. */
    val idFotoPublicacion: Int?
)

@Serializable
data class ListaNotificacionesResponse(
    val totalNoLeidas: Int,
    val pagina: Int,
    val porPagina: Int,
    val notificaciones: List<NotificacionResponse>
)

@Serializable
data class NoLeidasResponse(val total: Int)

/** Dato que la API guarda para el aviso. Es un objeto JSON plano, por ejemplo {"titulo": "Casa"}. */
data class NuevaNotificacion(
    val usuarioId: Int,
    val tipo: TipoNotificacion,
    val datosJson: String,
    val idPublicacion: Int? = null,
    val idReporte: Int? = null
)
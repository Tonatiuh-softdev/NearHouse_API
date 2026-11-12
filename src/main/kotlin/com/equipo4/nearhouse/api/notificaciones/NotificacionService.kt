package com.equipo4.nearhouse.api.notificaciones

import com.equipo4.nearhouse.api.common.ValidacionException
import com.equipo4.nearhouse.api.common.NoEncontradoException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Guarda los avisos del usuario. Esta etapa solo los guarda y los lista; el envío a la barra
 * del celular (Firebase) se agrega en la siguiente etapa.
 *
 * Regla del documento: si guardar un aviso falla, la acción principal (publicar, reportar, pagar)
 * no se cae. Por eso [notificar] nunca lanza excepción hacia afuera.
 */
class NotificacionService(private val repositorio: NotificacionRepository) {

    private val log = LoggerFactory.getLogger(NotificacionService::class.java)

    /**
     * Guarda un aviso. [datos] son los valores para el texto (título, motivo, plan, fecha...).
     * Nunca lanza excepción: si falla, solo se registra el error (sin datos personales).
     */
    suspend fun notificar(
        usuarioId: Int,
        tipo: TipoNotificacion,
        datos: Map<String, String> = emptyMap(),
        idPublicacion: Int? = null,
        idReporte: Int? = null
    ) {
        try {
            val json = buildJsonObject {
                datos.forEach { (clave, valor) -> put(clave, JsonPrimitive(valor)) }
            }.toString()
            repositorio.crear(
                NuevaNotificacion(
                    usuarioId = usuarioId,
                    tipo = tipo,
                    datosJson = json,
                    idPublicacion = idPublicacion,
                    idReporte = idReporte
                )
            )
        } catch (e: Exception) {
            // Sin título, texto ni datos del usuario: solo el tipo y el id interno
            log.error("No se pudo guardar el aviso {} del usuario {}: {}", tipo, usuarioId, e.message)
        }
    }

    suspend fun listar(usuarioId: Int, pagina: Int): ListaNotificacionesResponse {
        if (pagina < 1) throw ValidacionException(listOf("La página debe ser 1 o mayor"))
        val porPagina = POR_PAGINA
        return ListaNotificacionesResponse(
            totalNoLeidas = repositorio.contarNoLeidas(usuarioId),
            pagina = pagina,
            porPagina = porPagina,
            notificaciones = repositorio.listar(usuarioId, pagina, porPagina)
        )
    }

    suspend fun noLeidas(usuarioId: Int): NoLeidasResponse =
        NoLeidasResponse(repositorio.contarNoLeidas(usuarioId))

    suspend fun marcarLeida(usuarioId: Int, id: Int) {
        val marcada = repositorio.marcarLeida(usuarioId, id)
        if (!marcada && !repositorio.existeDeUsuario(usuarioId, id)) {
            throw NoEncontradoException("La notificación no existe")
        }
    }

    suspend fun marcarTodasLeidas(usuarioId: Int) {
        repositorio.marcarTodasLeidas(usuarioId)
    }

    suspend fun borrar(usuarioId: Int, id: Int) {
        if (!repositorio.borrar(usuarioId, id)) {
            throw NoEncontradoException("La notificación no existe")
        }
    }

    suspend fun borrarTodas(usuarioId: Int) {
        repositorio.borrarTodas(usuarioId)
    }

    /** Corre la limpieza diaria. La llama la API al arrancar y cada 24 horas. */
    suspend fun limpiar(): Int = repositorio.limpiar()

    private companion object {
        const val POR_PAGINA = 20
    }
}
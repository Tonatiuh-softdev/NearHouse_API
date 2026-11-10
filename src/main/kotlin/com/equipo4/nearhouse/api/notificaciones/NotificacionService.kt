package com.equipo4.nearhouse.api.notificaciones

import com.equipo4.nearhouse.api.common.NoEncontradoException
import com.equipo4.nearhouse.api.common.ValidacionException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Guarda los avisos del usuario y los manda a la barra del celular (Firebase).
 *
 * Regla del documento: si guardar o mandar un aviso falla, la acción principal (publicar, reportar, pagar)
 * no se cae. Por eso [notificar] nunca lanza excepción hacia afuera.
 */
class NotificacionService(
    private val repositorio: NotificacionRepository,
    private val dispositivos: DispositivoRepository,
    private val push: EnviadorPush,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val log = LoggerFactory.getLogger(NotificacionService::class.java)

    /**
     * Guarda un aviso y lo manda a los celulares del usuario. [datos] son los valores para el texto
     * (título, motivo, plan, fecha...). Nunca lanza excepción: si falla, solo se registra el error
     * (sin datos personales).
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
            log.error("No se pudo guardar el aviso {} del usuario {}: {}", tipo, usuarioId, e.message)
            return
        }

        // El envío al celular va en segundo plano: no retrasa ni tumba la acción principal
        scope.launch {
            try {
                enviarPush(usuarioId, tipo, datos, idPublicacion, idReporte)
            } catch (e: Exception) {
                log.warn("No se pudo enviar el push {} al usuario {}: {}", tipo, usuarioId, e.message)
            }
        }
    }

    private suspend fun enviarPush(
        usuarioId: Int,
        tipo: TipoNotificacion,
        datos: Map<String, String>,
        idPublicacion: Int?,
        idReporte: Int?
    ) {
        for (d in dispositivos.deUsuario(usuarioId)) {
            val texto = TextosPush.armar(tipo, datos, d.idioma)
            // Para que la app abra la pantalla correcta al tocar el aviso
            val payload = buildMap {
                put("tipo", tipo.name)
                idPublicacion?.let { put("idPublicacion", it.toString()) }
                idReporte?.let { put("idReporte", it.toString()) }
            }
            if (push.enviar(d.token, texto.titulo, texto.cuerpo, payload) == ResultadoPush.TOKEN_INVALIDO) {
                dispositivos.borrarToken(d.token)
            }
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
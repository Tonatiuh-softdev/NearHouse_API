package com.equipo4.nearhouse.api.reportes

import com.equipo4.nearhouse.api.auth.Roles
import com.equipo4.nearhouse.api.common.ApiException
import com.equipo4.nearhouse.api.common.ConflictoException
import com.equipo4.nearhouse.api.common.CuerpoInvalidoException
import com.equipo4.nearhouse.api.common.DemasiadasSolicitudesException
import com.equipo4.nearhouse.api.common.NoEncontradoException
import com.equipo4.nearhouse.api.common.ProhibidoException
import com.equipo4.nearhouse.api.common.ValidacionException
import com.equipo4.nearhouse.api.notificaciones.NotificacionService
import com.equipo4.nearhouse.api.notificaciones.TipoNotificacion
import com.equipo4.nearhouse.api.publicaciones.FotoGuardada
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json

/**
 * Lo necesario para avisar al resolver un reporte.
 * [reportanteId] puede ser nulo: si la cuenta de quien reportó se eliminó, el reporte se queda sin autor.
 * En reportes de publicación vienen [publicacionId] y [duenoId]; en reportes de usuario, [usuarioReportadoId].
 * [titulo] es el título de la publicación o el nombre de la cuenta reportada.
 */
data class AvisoReporte(
    val reportanteId: Int?,
    val duenoId: Int?,
    val publicacionId: Int?,
    val usuarioReportadoId: Int?,
    val titulo: String,
) {
    /** Datos del aviso. En reportes de usuario va su id para que la app abra su perfil. */
    fun datos(): Map<String, String> = buildMap {
        put("titulo", titulo)
        usuarioReportadoId?.let { put("usuarioReportado", it.toString()) }
    }
}

/** 403 con código: la cuenta del superadministrador no se puede reportar. */
class NoReportableException : ApiException(
    HttpStatusCode.Forbidden, "Esta cuenta no se puede reportar", codigo = "NO_REPORTABLE"
)

/** Reglas de negocio de reportes. Las validaciones siguen el orden de la especificación. */
class ReporteService(
    private val repositorio: ReporteRepository,
    private val notificaciones: NotificacionService,
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun crear(
        usuarioId: Int,
        publicacionId: Int,
        datosJson: String?,
        fotos: List<ByteArray>
    ): ReporteCreadoResponse {
        // 1. La publicación existe y está visible
        val publicacion = repositorio.buscarVisible(publicacionId)
            ?: throw NoEncontradoException("La publicación no existe o ya no está disponible")

        // 2. No es tuya
        if (publicacion.vendedorId == usuarioId) {
            throw ProhibidoException("No puedes reportar tu propia publicación")
        }

        // 3 a 5. Categoría y motivo, descripción e imágenes
        val datos = validarDatos(datosJson, fotos, MOTIVOS_POR_CATEGORIA)

        // 6. Ya tiene un reporte pendiente de esta publicación
        if (repositorio.existePendiente(publicacionId, usuarioId)) {
            throw ConflictoException("Ya reportaste esta publicación; está en revisión")
        }

        // 7. Límite diario (cuenta reportes de publicaciones y de usuarios)
        exigirLimiteDiario(usuarioId)

        val creado = repositorio.crear(
            usuarioId, ObjetivoReporte.Publicacion(publicacionId),
            datos.categoria, datos.motivo, datos.descripcion, datos.imagenes
        )
        notificaciones.notificar(
            usuarioId,
            TipoNotificacion.REPORTE_ENVIADO,
            mapOf("titulo" to publicacion.titulo),
            idPublicacion = publicacionId,
            idReporte = creado.id,
        )
        return creado
    }

    /**
     * Reporte a un usuario (se entra por la bandera de su perfil).
     * Mismo formato que el de publicaciones: "datos" (JSON) y de 0 a 5 imágenes en "fotos".
     */
    suspend fun crearDeUsuario(
        usuarioId: Int,
        reportadoId: Int,
        datosJson: String?,
        fotos: List<ByteArray>
    ): ReporteCreadoResponse {
        // 1. La cuenta existe y está activa
        val reportado = repositorio.buscarUsuarioReportable(reportadoId)
            ?: throw NoEncontradoException("El usuario no existe o ya no está disponible")

        // 2. No eres tú, y no es el superadministrador
        if (reportado.id == usuarioId) throw ProhibidoException("No puedes reportarte a ti mismo")
        if (reportado.rol == Roles.SUPER_ADMINISTRADOR) throw NoReportableException()

        // 3 a 5. Categoría y motivo, descripción e imágenes
        val datos = validarDatos(datosJson, fotos, MOTIVOS_USUARIO_POR_CATEGORIA)

        // 6. Ya tiene un reporte pendiente de esta cuenta
        if (repositorio.existePendienteUsuario(reportadoId, usuarioId)) {
            throw ConflictoException("Ya reportaste a este usuario; está en revisión")
        }

        // 7. Límite diario
        exigirLimiteDiario(usuarioId)

        val creado = repositorio.crear(
            usuarioId, ObjetivoReporte.Usuario(reportadoId),
            datos.categoria, datos.motivo, datos.descripcion, datos.imagenes
        )
        notificaciones.notificar(
            usuarioId,
            TipoNotificacion.REPORTE_ENVIADO,
            mapOf("titulo" to reportado.nombre, "usuarioReportado" to reportadoId.toString()),
            idReporte = creado.id,
        )
        return creado
    }

    private class DatosReporte(
        val categoria: String,
        val motivo: String,
        val descripcion: String,
        val imagenes: List<FotoEvidencia>
    )

    /** Valida el JSON y las fotos. [motivosPorCategoria] cambia según lo que se reporta. */
    private fun validarDatos(
        datosJson: String?,
        fotos: List<ByteArray>,
        motivosPorCategoria: Map<String, Set<String>>
    ): DatosReporte {
        val req = leerDatos(datosJson)
        val errores = mutableListOf<String>()

        // Categoría, y el motivo debe pertenecer a esa categoría
        val categoria = req.categoria.trim().uppercase()
        val motivo = req.motivo.trim().uppercase()
        if (categoria !in motivosPorCategoria) {
            errores += "La categoría no es válida"
        } else if (motivo !in motivosPorCategoria.getValue(categoria)) {
            errores += "El motivo no corresponde a la categoría elegida"
        }

        // Descripción: sin espacios al inicio ni al final, de 10 a 200 caracteres
        val descripcion = req.descripcion
        if (descripcion != descripcion.trim()) {
            errores += "La descripción no puede empezar ni terminar con espacios"
        }
        if (descripcion.length !in MIN_DESCRIPCION..MAX_DESCRIPCION) {
            errores += "La descripción debe tener entre $MIN_DESCRIPCION y $MAX_DESCRIPCION caracteres"
        }

        // Imágenes (0 a 5, máximo 2 MB cada una, validadas por su contenido)
        val imagenes = validarFotos(fotos, errores)

        if (errores.isNotEmpty()) throw ValidacionException(errores)
        return DatosReporte(categoria, motivo, descripcion, imagenes)
    }

    private suspend fun exigirLimiteDiario(usuarioId: Int) {
        if (repositorio.reportesUltimas24h(usuarioId) >= MAX_REPORTES_DIA) {
            throw DemasiadasSolicitudesException("Llegaste al límite de reportes por hoy. Intenta mañana.")
        }
    }

    private fun leerDatos(datosJson: String?): CrearReporteRequest {
        if (datosJson.isNullOrBlank()) throw CuerpoInvalidoException()
        return try {
            json.decodeFromString<CrearReporteRequest>(datosJson)
        } catch (e: IllegalArgumentException) {   // incluye SerializationException
            throw CuerpoInvalidoException()
        }
    }

    // ---------- Administrador ----------

    suspend fun listarAdmin(rol: String, estado: String?, pagina: Int?): List<ReporteListaResponse> {
        exigirAdmin(rol)
        val filtro = estado?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: "PENDIENTE"
        if (filtro !in ESTADOS_REPORTE) {
            throw ValidacionException(listOf("El estado debe ser PENDIENTE, PROCEDENTE o DESCARTADO"))
        }
        val numeroPagina = pagina ?: 1
        if (numeroPagina < 1) throw ValidacionException(listOf("La página debe ser 1 o mayor"))
        return repositorio.listarAdmin(filtro, numeroPagina)
    }

    suspend fun detalleAdmin(rol: String, id: Int): ReporteDetalleResponse {
        exigirAdmin(rol)
        return repositorio.detalleAdmin(id) ?: throw NoEncontradoException("El reporte no existe")
    }

    suspend fun evidencia(rol: String, id: Int): FotoGuardada {
        exigirAdmin(rol)
        return repositorio.buscarEvidencia(id) ?: throw NoEncontradoException("La evidencia no existe")
    }

    suspend fun resolver(rol: String, revisorId: Int, id: Int, req: ResolverReporteRequest) {
        exigirAdmin(rol)
        if (req.estado !in setOf("PROCEDENTE", "DESCARTADO")) {
            throw ValidacionException(listOf("El estado debe ser PROCEDENTE o DESCARTADO"))
        }
        val nota = req.nota.trim()
        if (nota.length !in 1..MAX_NOTA) {
            throw ValidacionException(listOf("La nota debe tener entre 1 y $MAX_NOTA caracteres"))
        }
        if (req.retirarPublicacion && req.estado != "PROCEDENTE") {
            throw ValidacionException(listOf("Solo se puede retirar la publicación si el reporte es PROCEDENTE"))
        }

        // Se leen los datos del aviso antes de resolver
        val aviso = repositorio.datosParaAviso(id)

        repositorio.resolver(id, revisorId, req.estado, nota, req.retirarPublicacion)

        if (aviso != null) {
            // La nota del administrador es interna: no se manda en ningún aviso
            val datos = aviso.datos()
            val reportante = aviso.reportanteId
            if (req.estado == "PROCEDENTE") {
                if (reportante != null) {
                    notificaciones.notificar(
                        reportante, TipoNotificacion.REPORTE_ATENDIDO, datos, aviso.publicacionId, id
                    )
                }
                val dueno = aviso.duenoId
                if (req.retirarPublicacion && dueno != null) {
                    notificaciones.notificar(
                        dueno, TipoNotificacion.PUBLICACION_RETIRADA, datos, aviso.publicacionId, id
                    )
                }
            } else if (reportante != null) {
                notificaciones.notificar(
                    reportante, TipoNotificacion.REPORTE_DESCARTADO, datos, aviso.publicacionId, id
                )
            }
        }
    }

    private fun exigirAdmin(rol: String) {
        if (rol !in ROLES_ADMIN) throw ProhibidoException("Solo los administradores pueden revisar reportes")
    }

    private fun validarFotos(fotos: List<ByteArray>, errores: MutableList<String>): List<FotoEvidencia> {
        if (fotos.size > MAX_FOTOS) {
            errores += "Máximo $MAX_FOTOS imágenes por reporte"
            return emptyList()
        }
        return fotos.mapIndexedNotNull { i, bytes ->
            val n = i + 1
            when {
                bytes.isEmpty() -> {
                    errores += "La imagen $n está vacía"
                    null
                }
                bytes.size > MAX_FOTO_BYTES -> {
                    errores += "La imagen $n pesa más de 2 MB"
                    null
                }
                else -> {
                    // Se revisan los primeros bytes, no el nombre ni el tipo que declara la app
                    val tipo = detectarTipoImagen(bytes)
                    if (tipo == null) {
                        errores += "La imagen $n debe ser JPG, PNG o WebP"
                        null
                    } else {
                        FotoEvidencia(bytes, tipo)
                    }
                }
            }
        }
    }

    private fun detectarTipoImagen(b: ByteArray): String? = when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size >= 8 && coincide(b, 0, FIRMA_PNG) -> "image/png"
        b.size >= 12 && coincide(b, 0, FIRMA_RIFF) && coincide(b, 8, FIRMA_WEBP) -> "image/webp"
        else -> null
    }

    private fun coincide(b: ByteArray, desde: Int, firma: ByteArray): Boolean =
        firma.indices.all { b[desde + it] == firma[it] }

    private companion object {
        const val MIN_DESCRIPCION = 10
        const val MAX_DESCRIPCION = 200
        const val MAX_FOTOS = 5
        const val MAX_FOTO_BYTES = 2 * 1024 * 1024
        const val MAX_REPORTES_DIA = 10
        val FIRMA_PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val FIRMA_RIFF = "RIFF".toByteArray()
        val FIRMA_WEBP = "WEBP".toByteArray()
        val ROLES_ADMIN = setOf(Roles.ADMINISTRADOR, Roles.SUPER_ADMINISTRADOR)
        val ESTADOS_REPORTE = setOf("PENDIENTE", "PROCEDENTE", "DESCARTADO")
        const val MAX_NOTA = 300
    }
}
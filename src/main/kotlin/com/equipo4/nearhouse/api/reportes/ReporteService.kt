package com.equipo4.nearhouse.api.reportes

import com.equipo4.nearhouse.api.common.ConflictoException
import com.equipo4.nearhouse.api.common.CuerpoInvalidoException
import com.equipo4.nearhouse.api.common.DemasiadasSolicitudesException
import com.equipo4.nearhouse.api.common.NoEncontradoException
import com.equipo4.nearhouse.api.common.ProhibidoException
import com.equipo4.nearhouse.api.common.ValidacionException
import kotlinx.serialization.json.Json
import com.equipo4.nearhouse.api.auth.Roles
import com.equipo4.nearhouse.api.publicaciones.FotoGuardada

/** Reglas de negocio de reportes. Las validaciones siguen el orden de la especificación. */
class ReporteService(private val repositorio: ReporteRepository) {

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

        val req = leerDatos(datosJson)
        val errores = mutableListOf<String>()

        // 3. Categoría, y el motivo debe pertenecer a esa categoría
        val categoria = req.categoria.trim().uppercase()
        val motivo = req.motivo.trim().uppercase()
        if (categoria !in MOTIVOS_POR_CATEGORIA) {
            errores += "La categoría no es válida"
        } else if (motivo !in MOTIVOS_POR_CATEGORIA.getValue(categoria)) {
            errores += "El motivo no corresponde a la categoría elegida"
        }

        // 4. Descripción: sin espacios al inicio ni al final, de 10 a 200 caracteres
        val descripcion = req.descripcion
        if (descripcion != descripcion.trim()) {
            errores += "La descripción no puede empezar ni terminar con espacios"
        }
        if (descripcion.length !in MIN_DESCRIPCION..MAX_DESCRIPCION) {
            errores += "La descripción debe tener entre $MIN_DESCRIPCION y $MAX_DESCRIPCION caracteres"
        }

        // 5. Imágenes (0 a 5, máximo 2 MB cada una, validadas por su contenido)
        val imagenes = validarFotos(fotos, errores)

        if (errores.isNotEmpty()) throw ValidacionException(errores)

        // 6. Ya tiene un reporte pendiente de esta publicación
        if (repositorio.existePendiente(publicacionId, usuarioId)) {
            throw ConflictoException("Ya reportaste esta publicación; está en revisión")
        }

        // 7. Límite diario
        if (repositorio.reportesUltimas24h(usuarioId) >= MAX_REPORTES_DIA) {
            throw DemasiadasSolicitudesException("Llegaste al límite de reportes por hoy. Intenta mañana.")
        }

        return repositorio.crear(usuarioId, publicacionId, categoria, motivo, descripcion, imagenes)
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
        repositorio.resolver(id, revisorId, req.estado, nota, req.retirarPublicacion)
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
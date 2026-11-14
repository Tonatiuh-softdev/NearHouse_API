package com.equipo4.nearhouse.api.apelaciones

import com.equipo4.nearhouse.api.auth.Roles
import com.equipo4.nearhouse.api.common.ConflictoException
import com.equipo4.nearhouse.api.common.CuerpoInvalidoException
import com.equipo4.nearhouse.api.common.NoAutorizadoException
import com.equipo4.nearhouse.api.common.NoEncontradoException
import com.equipo4.nearhouse.api.common.ProhibidoException
import com.equipo4.nearhouse.api.common.ValidacionException
import com.equipo4.nearhouse.api.notificaciones.NotificacionService
import com.equipo4.nearhouse.api.notificaciones.TipoNotificacion
import com.equipo4.nearhouse.api.publicaciones.FotoGuardada
import com.equipo4.nearhouse.api.reportes.FotoEvidencia
import com.equipo4.nearhouse.api.verificacion.EnviadorCorreo
import com.equipo4.nearhouse.api.verificacion.PlantillasCorreo
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Reglas de las apelaciones: la defensa de un usuario contra una medida de un administrador.
 * - Anuncio retirado por un reporte: lo apela su dueño desde "Mis anuncios".
 * - Cuenta suspendida: se apela desde el inicio de sesión con un comprobante (la cuenta no tiene sesión).
 * Solo una apelación por medida. La revisa un administrador distinto al que tomó la medida.
 */
class ApelacionService(
    private val repositorio: ApelacionRepository,
    private val notificaciones: NotificacionService,
    private val enviadorCorreo: EnviadorCorreo,
    private val tokens: ApelacionTokens,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val log = LoggerFactory.getLogger(ApelacionService::class.java)

    // ---------- Quien apela ----------

    suspend fun crearDePublicacion(
        usuarioId: Int,
        publicacionId: Int,
        datosJson: String?,
        fotos: List<ByteArray>
    ): ApelacionCreadaResponse {
        // 1. Es tuyo y lo retiraron por un reporte
        val medida = repositorio.retiroApelable(publicacionId, usuarioId)
            ?: throw NoEncontradoException("Este anuncio no tiene una decisión que se pueda apelar")

        // 2. Descripción e imágenes
        val (descripcion, imagenes) = validarDatos(leerDatos(datosJson), fotos)

        // 3. Solo una apelación por medida
        if (repositorio.existeApelacionDelRetiro(publicacionId)) {
            throw ConflictoException("Ya apelaste esta decisión. Solo se puede apelar una vez")
        }

        val creada = repositorio.crearDePublicacion(usuarioId, publicacionId, descripcion, imagenes)
        notificaciones.notificar(
            usuarioId,
            TipoNotificacion.APELACION_ENVIADA,
            mapOf("titulo" to medida.titulo, "apelacion" to "PUBLICACION"),
            idPublicacion = publicacionId,
        )
        return creada
    }

    /** Apelación de una suspensión. El comprobante viene en "datos" (lo dio el inicio de sesión). */
    suspend fun crearDeSuspension(datosJson: String?, fotos: List<ByteArray>): ApelacionCreadaResponse {
        val req = leerDatos(datosJson)

        // 1. Comprobante válido y de la suspensión actual
        val datosToken = req.token?.takeIf { it.isNotBlank() }?.let { tokens.verificar(it) }
            ?: throw NoAutorizadoException("Tu sesión para apelar venció. Vuelve a iniciar sesión para apelar")
        val suspension = repositorio.suspensionVigente(datosToken.usuarioId)
            ?: throw NoEncontradoException("Tu cuenta ya no está suspendida. Vuelve a iniciar sesión")
        if (suspension.desde.toInstant().toEpochMilli() != datosToken.fechaMedidaMs) {
            throw NoAutorizadoException("Tu suspensión cambió. Vuelve a iniciar sesión para apelar")
        }

        // 2. Descripción e imágenes
        val (descripcion, imagenes) = validarDatos(req, fotos)

        // 3. Solo una apelación por suspensión
        if (repositorio.apelacionDeSuspensionVigente(datosToken.usuarioId) != null) {
            throw ConflictoException("Ya apelaste esta suspensión. Solo se puede apelar una vez")
        }

        val creada = repositorio.crearDeSuspension(datosToken.usuarioId, descripcion, imagenes)
        notificaciones.notificar(
            datosToken.usuarioId,
            TipoNotificacion.APELACION_ENVIADA,
            mapOf("apelacion" to "SUSPENSION"),
        )
        return creada
    }

    /**
     * Error del inicio de sesión para una cuenta suspendida: motivo, hasta cuándo y, si todavía
     * no apela esta suspensión, un comprobante para hacerlo. Solo se llama con la contraseña
     * (o Google) ya verificada.
     */
    suspend fun errorDeSuspension(usuarioId: Int): CuentaSuspendidaException {
        val suspension = repositorio.suspensionVigente(usuarioId)
            ?: return CuentaSuspendidaException("—", null, null, null, null)
        val apelacion = repositorio.apelacionDeSuspensionVigente(usuarioId)
        return CuentaSuspendidaException(
            motivo = suspension.motivo,
            hasta = suspension.hasta?.toLocalDate()?.toString(),
            apelacionToken = if (apelacion == null) {
                tokens.crear(usuarioId, suspension.desde.toInstant().toEpochMilli())
            } else {
                null
            },
            apelacionEstado = apelacion?.estado,
            apelacionNota = apelacion?.nota
        )
    }

    /** Anuncios del usuario retirados por un reporte y el estado de su apelación (para "Mis anuncios"). */
    suspend fun retiradasPorReporte(usuarioId: Int): Map<Int, ApelacionResumen> =
        repositorio.retiradasPorReporte(usuarioId)

    // ---------- Administrador ----------

    suspend fun listarAdmin(rol: String, estado: String?, pagina: Int?): List<ApelacionListaResponse> {
        exigirAdmin(rol)
        val filtro = estado?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: "PENDIENTE"
        if (filtro !in ESTADOS) {
            throw ValidacionException(listOf("El estado debe ser PENDIENTE, ACEPTADA o RECHAZADA"))
        }
        val numeroPagina = pagina ?: 1
        if (numeroPagina < 1) throw ValidacionException(listOf("La página debe ser 1 o mayor"))
        return repositorio.listarAdmin(filtro, numeroPagina)
    }

    suspend fun detalleAdmin(rol: String, id: Int): ApelacionDetalleResponse {
        exigirAdmin(rol)
        return repositorio.detalleAdmin(id) ?: throw NoEncontradoException("La apelación no existe")
    }

    suspend fun evidencia(rol: String, id: Int): FotoGuardada {
        exigirAdmin(rol)
        return repositorio.buscarEvidencia(id) ?: throw NoEncontradoException("La evidencia no existe")
    }

    suspend fun resolver(rol: String, revisorId: Int, id: Int, req: ResolverApelacionRequest) {
        exigirAdmin(rol)
        if (req.estado !in setOf("ACEPTADA", "RECHAZADA")) {
            throw ValidacionException(listOf("El estado debe ser ACEPTADA o RECHAZADA"))
        }
        val nota = req.nota.trim()
        if (nota.length !in 1..MAX_NOTA) {
            throw ValidacionException(listOf("La respuesta debe tener entre 1 y $MAX_NOTA caracteres"))
        }

        val aviso = repositorio.resolver(id, revisorId, rolesAdministrables(rol), req.estado, nota)
        val aceptada = req.estado == "ACEPTADA"

        // La respuesta del administrador SÍ se manda: es la contestación a quien apeló
        val datos = buildMap {
            put("apelacion", aviso.tipo)
            put("motivo", nota)
            if (aviso.titulo.isNotEmpty()) put("titulo", aviso.titulo)
        }
        notificaciones.notificar(
            aviso.apelanteId,
            if (aceptada) TipoNotificacion.APELACION_ACEPTADA else TipoNotificacion.APELACION_RECHAZADA,
            datos,
            idPublicacion = aviso.publicacionId,
        )

        // Una cuenta suspendida no puede entrar a ver sus avisos: también se le manda un correo
        if (aviso.tipo == "SUSPENSION") {
            try {
                enviadorCorreo.enviar(PlantillasCorreo.apelacionResuelta(aviso.correo, aceptada, nota))
            } catch (e: Exception) {
                log.warn("No se pudo mandar el correo de la apelación {}: {}", id, e.message)
            }
        }
    }

    // ---------- Auxiliares ----------

    private fun leerDatos(datosJson: String?): CrearApelacionRequest {
        if (datosJson.isNullOrBlank()) throw CuerpoInvalidoException()
        return try {
            json.decodeFromString<CrearApelacionRequest>(datosJson)
        } catch (e: IllegalArgumentException) {   // incluye SerializationException
            throw CuerpoInvalidoException()
        }
    }

    /** Descripción sin espacios al inicio ni al final (10 a 500 caracteres) y de 0 a 5 imágenes. */
    private fun validarDatos(req: CrearApelacionRequest, fotos: List<ByteArray>): Pair<String, List<FotoEvidencia>> {
        val errores = mutableListOf<String>()
        val descripcion = req.descripcion
        if (descripcion != descripcion.trim()) {
            errores += "La explicación no puede empezar ni terminar con espacios"
        }
        if (descripcion.length !in MIN_DESCRIPCION..MAX_DESCRIPCION) {
            errores += "La explicación debe tener entre $MIN_DESCRIPCION y $MAX_DESCRIPCION caracteres"
        }
        val imagenes = validarFotos(fotos, errores)
        if (errores.isNotEmpty()) throw ValidacionException(errores)
        return descripcion to imagenes
    }

    private fun validarFotos(fotos: List<ByteArray>, errores: MutableList<String>): List<FotoEvidencia> {
        if (fotos.size > MAX_FOTOS) {
            errores += "Máximo $MAX_FOTOS imágenes por apelación"
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

    private fun exigirAdmin(rol: String) {
        if (rol !in ROLES_ADMIN) throw ProhibidoException("Solo los administradores pueden revisar apelaciones")
    }

    /** Cuentas que puede administrar (igual que el panel de usuarios): nunca las de su mismo nivel. */
    private fun rolesAdministrables(rol: String): Set<String> = when (rol) {
        Roles.SUPER_ADMINISTRADOR -> setOf(Roles.COMPRADOR, Roles.VENDEDOR, Roles.ADMINISTRADOR)
        Roles.ADMINISTRADOR -> setOf(Roles.COMPRADOR, Roles.VENDEDOR)
        else -> emptySet()
    }

    private companion object {
        const val MIN_DESCRIPCION = 10
        const val MAX_DESCRIPCION = 500
        const val MAX_FOTOS = 5
        const val MAX_FOTO_BYTES = 2 * 1024 * 1024
        const val MAX_NOTA = 300
        val FIRMA_PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val FIRMA_RIFF = "RIFF".toByteArray()
        val FIRMA_WEBP = "WEBP".toByteArray()
        val ROLES_ADMIN = setOf(Roles.ADMINISTRADOR, Roles.SUPER_ADMINISTRADOR)
        val ESTADOS = setOf("PENDIENTE", "ACEPTADA", "RECHAZADA")
    }
}

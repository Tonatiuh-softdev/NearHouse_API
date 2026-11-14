package com.equipo4.nearhouse.api.publicaciones

import com.equipo4.nearhouse.api.apelaciones.ApelacionService
import com.equipo4.nearhouse.api.auth.Roles
import com.equipo4.nearhouse.api.common.ConflictoException
import com.equipo4.nearhouse.api.common.CuerpoInvalidoException
import com.equipo4.nearhouse.api.common.LimiteAnunciosException
import com.equipo4.nearhouse.api.common.NoEncontradoException
import com.equipo4.nearhouse.api.common.PlanRequeridoException
import com.equipo4.nearhouse.api.common.ProhibidoException
import com.equipo4.nearhouse.api.common.ValidacionException
import com.equipo4.nearhouse.api.notificaciones.NotificacionService
import com.equipo4.nearhouse.api.notificaciones.TipoNotificacion
import kotlinx.serialization.json.Json

/**
 * Reglas de negocio de publicaciones: quién puede publicar, qué puede cambiar cada rol
 * y que los datos y las fotos sean válidos. Las reglas coinciden con las restricciones
 * de la base de datos (migraciones 02 y 12 y 13).
 */
class PublicacionService(
    private val repositorio: PublicacionRepository,
    private val notificaciones: NotificacionService,
    /** Para marcar en "Mis anuncios" los retirados por un reporte y el estado de su apelación. */
    private val apelaciones: ApelacionService? = null,
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun crear(usuarioId: Int, datosJson: String?, fotos: List<ByteArray>): PublicacionCreadaResponse {
        exigirCupo(exigirPlanPublicador(usuarioId))

        val req = leerDatos(datosJson)
        val errores = mutableListOf<String>()
        val publicacion = validarDatos(req, errores)
        val fotosValidas = validarFotos(fotos, errores)
        if (errores.isNotEmpty()) throw ValidacionException(errores)

        val creada = repositorio.crear(usuarioId, publicacion, fotosValidas)
        notificaciones.notificar(
            usuarioId,
            TipoNotificacion.PUBLICACION_EN_REVISION,
            mapOf("titulo" to publicacion.titulo),
            idPublicacion = creada.id,
        )
        return creada
    }

    // ---------- Consultas ----------

    /**
     * Inicio y Mapa: se quitan los anuncios que exceden el límite del plan de su vendedor y el resto
     * se ordena según el plan (ver [RankingPublicaciones]).
     */
    suspend fun listarPublicas(usuarioLogueadoId: Int? = null): List<PublicacionResponse> {
        val visibles = repositorio.listar(soloPublicadas = true, estado = null, usuarioVendedorId = null, usuarioLogueadoId = usuarioLogueadoId)
        return RankingPublicaciones.ordenar(RankingPublicaciones.aplicarLimites(visibles), usuarioLogueadoId)
    }

    /** Mis anuncios: se marcan los que no se muestran por exceder el límite del plan. */
    suspend fun listarMias(usuarioId: Int): List<PublicacionResponse> {
        val mias = repositorio.listar(soloPublicadas = false, estado = null, usuarioVendedorId = usuarioId, usuarioLogueadoId = usuarioId)
        val ocultas = RankingPublicaciones.idsFueraDeLimite(mias)
        val retiradas = apelaciones?.retiradasPorReporte(usuarioId).orEmpty()
        if (ocultas.isEmpty() && retiradas.isEmpty()) return mias
        return mias.map { anuncio ->
            val apelacion = retiradas[anuncio.id]
            anuncio.copy(
                ocultaPorLimite = anuncio.id in ocultas,
                retiradaPorReporte = apelacion != null,
                apelacionEstado = apelacion?.estado,
                apelacionNota = apelacion?.nota
            )
        }
    }

    /** Para el botón "Crear anuncio": anuncios activos, límite del plan y si puede crear otro. */
    suspend fun cupo(usuarioId: Int): CupoResponse {
        val estado = repositorio.estadoPublicador(usuarioId)
            ?: throw NoEncontradoException("Usuario no encontrado")
        val esAdmin = estado.rol in ROLES_ADMIN
        val limite = if (esAdmin) null else estado.limite
        val codigo = when {
            !estado.cuentaActiva -> "CUENTA_INACTIVA"
            esAdmin -> null
            estado.rol != Roles.VENDEDOR || !estado.planVigente -> "PLAN_REQUERIDO"
            limite != null && estado.activas >= limite -> "LIMITE_ANUNCIOS"
            else -> null
        }
        return CupoResponse(
            plan = if (esAdmin) null else estado.plan,
            limite = limite,
            activas = estado.activas,
            puedePublicar = codigo == null,
            codigo = codigo
        )
    }

    suspend fun obtener(id: Int, usuarioId: Int, rol: String, usuarioLogueadoId: Int? = null): PublicacionResponse {
        val anuncio = repositorio.buscarPorId(id, usuarioLogueadoId ?: usuarioId) ?: throw NoEncontradoException("El anuncio no existe")
        val visible = anuncio.vendedor.id == usuarioId || rol in ROLES_ADMIN || repositorio.esVisible(id)
        if (!visible) throw NoEncontradoException("El anuncio no existe")
        return anuncio
    }

    suspend fun foto(id: Int): FotoGuardada =
        repositorio.buscarFoto(id) ?: throw NoEncontradoException("La foto no existe")

    // ---------- Acciones del vendedor sobre sus anuncios ----------

    private suspend fun propio(id: Int, usuarioId: Int): PublicacionResponse {
        val anuncio = repositorio.buscarPorId(id)
        if (anuncio == null || anuncio.vendedor.id != usuarioId) throw NoEncontradoException("El anuncio no existe")
        return anuncio
    }

    /**
     * Pausar (activa = false) no requiere plan. Reactivar (activa = true) requiere plan vigente,
     * y nunca se puede si la desactivó un administrador.
     */
    suspend fun cambiarActiva(usuarioId: Int, id: Int, activa: Boolean): PublicacionResponse {
        val anuncio = propio(id, usuarioId)
        if (anuncio.estado != ESTADO_PUBLICADA) {
            throw ConflictoException("Solo se puede pausar o reactivar un anuncio que ya fue publicado")
        }
        if (activa && anuncio.desactivadaPorAdmin) {
            throw ProhibidoException("Este anuncio fue desactivado por un administrador. Solo un administrador puede reactivarlo.")
        }
        if (activa) {
            val estado = exigirPlanPublicador(usuarioId)
            // Reactivar ocupa un lugar del plan (si ya estaba activo, no ocupa uno nuevo)
            if (!anuncio.activa) exigirCupo(estado)
        }
        repositorio.cambiarActiva(id, usuarioId, activa)
        return repositorio.buscarPorId(id) ?: throw NoEncontradoException("El anuncio no existe")
    }

    /** Eliminar no requiere plan: cualquier dueño puede borrar sus anuncios. */
    suspend fun eliminar(usuarioId: Int, id: Int) {
        propio(id, usuarioId)
        repositorio.eliminar(id, usuarioId)
    }

    suspend fun editar(usuarioId: Int, id: Int, datosJson: String?, fotosNuevas: List<ByteArray>): PublicacionResponse {
        val estadoPublicador = exigirPlanPublicador(usuarioId)

        val actual = propio(id, usuarioId)
        if (actual.desactivadaPorAdmin) {
            throw ProhibidoException("Este anuncio fue desactivado por un administrador y no se puede editar.")
        }
        // Un anuncio rechazado no ocupa lugar; al corregirlo vuelve a revisión y sí lo ocupa
        if (actual.estado == ESTADO_RECHAZADA && actual.activa) exigirCupo(estadoPublicador)
        val req = leerDatos(datosJson)
        val errores = mutableListOf<String>()
        val publicacion = validarDatos(req, errores)

        val eliminar = (req.fotosEliminar +
            if (req.reemplazaPortada) listOfNotNull(actual.fotos.firstOrNull()) else emptyList()).distinct()
        if (eliminar.any { it !in actual.fotos }) errores += "Alguna foto a quitar no pertenece a este anuncio"

        val nuevas = if (fotosNuevas.isEmpty()) emptyList() else validarFotos(fotosNuevas, errores)
        val total = actual.fotos.count { it !in eliminar } + nuevas.size
        if (total !in 1..MAX_FOTOS) errores += "El anuncio debe quedar con entre 1 y $MAX_FOTOS fotos"
        if (req.reemplazaPortada && fotosNuevas.isEmpty()) errores += "Para reemplazar la portada agrega una foto nueva"
        if (errores.isNotEmpty()) throw ValidacionException(errores)

        repositorio.editar(id, usuarioId, publicacion, eliminar, req.reemplazaPortada, nuevas)
        val editada = repositorio.buscarPorId(id) ?: throw NoEncontradoException("El anuncio no existe")
        notificaciones.notificar(
            usuarioId,
            TipoNotificacion.PUBLICACION_EN_REVISION,
            mapOf("titulo" to editada.titulo),
            idPublicacion = id,
        )
        return editada
    }

    // ---------- Administrador ----------

    suspend fun listarParaAdmin(rol: String, estado: String?): List<PublicacionResponse> {
        exigirAdmin(rol)
        val filtro = estado?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        if (filtro != null && filtro !in ESTADOS) {
            throw ValidacionException(listOf("El estado debe ser EN_REVISION, PUBLICADA o RECHAZADA"))
        }
        return repositorio.listar(soloPublicadas = false, estado = filtro)
    }

    suspend fun aprobar(rol: String, revisorId: Int, id: Int): PublicacionResponse {
        exigirAdmin(rol)
        exigirAnuncioAjeno(id, revisorId)
        revisar(id, revisorId, ESTADO_PUBLICADA, null)
        val anuncio = repositorio.buscarPorId(id) ?: throw NoEncontradoException("El anuncio no existe")
        notificaciones.notificar(
            anuncio.vendedor.id,
            TipoNotificacion.PUBLICACION_APROBADA,
            mapOf("titulo" to anuncio.titulo),
            idPublicacion = id,
        )
        return anuncio
    }

    suspend fun rechazar(rol: String, revisorId: Int, id: Int, motivo: String): PublicacionResponse {
        exigirAdmin(rol)
        val limpio = motivo.trim()
        if (limpio.length !in MIN_MOTIVO..MAX_MOTIVO) {
            throw ValidacionException(listOf("El motivo debe tener entre $MIN_MOTIVO y $MAX_MOTIVO caracteres"))
        }
        exigirAnuncioAjeno(id, revisorId)
        revisar(id, revisorId, ESTADO_RECHAZADA, limpio)
        val anuncio = repositorio.buscarPorId(id) ?: throw NoEncontradoException("El anuncio no existe")
        notificaciones.notificar(
            anuncio.vendedor.id,
            TipoNotificacion.PUBLICACION_RECHAZADA,
            mapOf("titulo" to anuncio.titulo, "motivo" to limpio),
            idPublicacion = id,
        )
        return anuncio
    }

    /** Desactiva un anuncio publicado. El motivo es obligatorio y queda en el historial. */
    suspend fun desactivarComoAdmin(rol: String, revisorId: Int, id: Int, motivo: String) {
        exigirAdmin(rol)
        val limpio = motivo.trim()
        if (limpio.length < MIN_MOTIVO_DESACTIVAR) {
            throw ValidacionException(listOf("El motivo debe tener al menos $MIN_MOTIVO_DESACTIVAR caracteres"))
        }
        val anuncio = exigirAnuncioAjeno(id, revisorId)
        if (anuncio.desactivadaPorAdmin) {
            throw ConflictoException("Este anuncio ya está desactivado")
        }
        if (!repositorio.cambiarActivaAdmin(id, revisorId, activa = false, motivo = limpio)) {
            throw ConflictoException("Solo se puede desactivar un anuncio publicado")
        }
        notificaciones.notificar(
            anuncio.vendedor.id,
            TipoNotificacion.PUBLICACION_RETIRADA,
            mapOf("titulo" to anuncio.titulo, "motivo" to limpio),
            idPublicacion = id,
        )
    }

    /** Reactiva un anuncio que desactivó un administrador. */
    suspend fun reactivarComoAdmin(rol: String, revisorId: Int, id: Int) {
        exigirAdmin(rol)
        val anuncio = exigirAnuncioAjeno(id, revisorId)
        if (!anuncio.desactivadaPorAdmin) {
            throw ConflictoException("Este anuncio no está desactivado por un administrador")
        }
        if (!repositorio.cambiarActivaAdmin(id, revisorId, activa = true, motivo = null)) {
            throw ConflictoException("Solo se puede reactivar un anuncio publicado")
        }
    }

    /** Eliminar como administrador: cualquier anuncio, sin importar su estado. */
    suspend fun eliminarComoAdmin(rol: String, id: Int) {
        exigirAdmin(rol)
        if (repositorio.buscarPorId(id) == null) throw NoEncontradoException("El anuncio no existe")
        repositorio.eliminarCualquiera(id)
    }

    private suspend fun revisar(id: Int, revisorId: Int, nuevoEstado: String, motivo: String?) {
        if (!repositorio.revisar(id, revisorId, nuevoEstado, motivo)) {
            if (repositorio.buscarPorId(id) == null) throw NoEncontradoException("El anuncio no existe")
            throw ConflictoException("Este anuncio ya fue revisado")
        }
    }

    // ---------- Permisos ----------

    private fun exigirAdmin(rol: String) {
        if (rol !in ROLES_ADMIN) throw ProhibidoException("Solo los administradores pueden revisar anuncios")
    }

    /** Un administrador no aprueba, rechaza ni modera sus propios anuncios: lo hace otro administrador. */
    private suspend fun exigirAnuncioAjeno(id: Int, revisorId: Int): PublicacionResponse {
        val anuncio = repositorio.buscarPorId(id) ?: throw NoEncontradoException("El anuncio no existe")
        if (anuncio.vendedor.id == revisorId) {
            throw ProhibidoException("No puedes revisar tu propio anuncio. Lo debe revisar otro administrador")
        }
        return anuncio
    }

    /**
     * El rol y el plan se leen de la base, no del token (el token puede estar viejo).
     * Admin y super admin pasan sin plan. Vendedor necesita plan vigente.
     */
    private suspend fun exigirPlanPublicador(usuarioId: Int): EstadoPublicador {
        val estado = repositorio.estadoPublicador(usuarioId)
            ?: throw NoEncontradoException("Usuario no encontrado")
        if (!estado.cuentaActiva) throw ProhibidoException("Tu cuenta no está activa")
        if (estado.rol in ROLES_ADMIN) return estado
        if (estado.rol != Roles.VENDEDOR || !estado.planVigente) throw PlanRequeridoException()
        return estado
    }

    /**
     * Revisa que el plan tenga lugar para un anuncio activo más (en revisión o publicado, no pausado).
     * Básico: 1, Profesional: 3, Premium: sin límite. Los administradores no tienen límite.
     */
    private fun exigirCupo(estado: EstadoPublicador) {
        if (estado.rol in ROLES_ADMIN) return
        val limite = estado.limite ?: return
        if (estado.activas >= limite) {
            val tuPlan = estado.plan?.let { "Tu plan $it" } ?: "Tu plan"
            val anuncios = if (limite == 1) "1 anuncio activo" else "$limite anuncios activos"
            throw LimiteAnunciosException(
                "$tuPlan permite $anuncios. Pausa o elimina uno, o cambia a un plan con más anuncios."
            )
        }
    }

    private fun leerDatos(datosJson: String?): CrearPublicacionRequest {
        if (datosJson.isNullOrBlank()) throw CuerpoInvalidoException()
        return try {
            json.decodeFromString<CrearPublicacionRequest>(datosJson)
        } catch (e: IllegalArgumentException) {
            throw CuerpoInvalidoException()
        }
    }

    // ---------- Datos ----------

    private fun validarDatos(req: CrearPublicacionRequest, errores: MutableList<String>): PublicacionNueva {
        val titulo = req.titulo.trim()
        if (titulo.isEmpty() || titulo.length > MAX_TITULO) {
            errores += "El título debe tener entre 1 y $MAX_TITULO caracteres"
        }

        val descripcion = req.descripcion.trim()
        if (descripcion.length !in MIN_DESCRIPCION..MAX_DESCRIPCION) {
            errores += "La descripción debe tener entre $MIN_DESCRIPCION y $MAX_DESCRIPCION caracteres"
        }

        val reglas = req.reglas?.trim()?.takeIf { it.isNotEmpty() }
        if (reglas != null && reglas.length > MAX_REGLAS) {
            errores += "Las reglas no pueden pasar de $MAX_REGLAS caracteres"
        }

        if (req.precio !in 1L..MAX_PRECIO) {
            errores += "El precio debe ser mayor que 0 y menor a 1,000,000,000"
        }

        val modalidad = when (req.modalidad.trim().lowercase()) {
            "venta" -> "Venta"
            "renta" -> "Renta"
            else -> {
                errores += "La modalidad debe ser Venta o Renta"
                "Venta"
            }
        }

        val tipo = req.tipo.trim().uppercase()
        if (tipo !in TIPOS) errores += "El tipo debe ser DEPARTAMENTO, CASA o TERRENO"

        val calle = req.calle.trim()
        if (calle.isEmpty() || calle.length > MAX_CALLE) errores += "La calle es obligatoria (máximo $MAX_CALLE caracteres)"

        val numero = req.numero.trim()
        if (numero.isEmpty() || numero.length > MAX_NUMERO) errores += "El número es obligatorio (máximo $MAX_NUMERO caracteres)"

        val colonia = req.colonia.trim()
        if (colonia.isEmpty() || colonia.length > MAX_COLONIA) {
            errores += "La colonia es obligatoria (máximo $MAX_COLONIA caracteres)"
        }

        val codigoPostal = req.codigoPostal?.trim()?.takeIf { it.isNotEmpty() }
        if (codigoPostal != null && !REGEX_CODIGO_POSTAL.matches(codigoPostal)) {
            errores += "El código postal debe tener 5 dígitos"
        }

        if (req.latitud !in -90.0..90.0 || req.longitud !in -180.0..180.0) {
            errores += "La ubicación no es válida"
        }

        var inmueble: DatosInmueble? = null
        var usoSuelo: String? = null

        val superficie = req.superficie ?: 0
        if (superficie !in 1..MAX_SUPERFICIE) errores += "La superficie debe ser mayor que 0"

        when (tipo) {
            "TERRENO" -> {
                usoSuelo = req.usoSuelo?.trim()?.uppercase()
                if (usoSuelo == null || usoSuelo !in USOS_SUELO) errores += "El uso de suelo debe ser HABITACIONAL o AGRICOLA"
            }

            "DEPARTAMENTO", "CASA" -> {
                val esCasa = tipo == "CASA"
                inmueble = DatosInmueble(
                    dormitorios = req.dormitorios.requerido("Las recámaras", 0..50, errores),
                    banos = req.banos.requerido("Los baños", 0..50, errores),
                    cochera = req.cochera.requerido("El estacionamiento", 0..50, errores),
                    piso = if (esCasa) null else req.piso.requerido("El piso", 0..99, errores),
                    niveles = if (esCasa) req.niveles.requerido("Los niveles", 1..10, errores) else null,
                    amueblado = !esCasa && req.amueblado,
                    mascotas = modalidad == "Renta" && req.mascotas,
                    elevador = !esCasa && req.elevador,
                    patio = esCasa && req.patio
                )
            }
        }

        return PublicacionNueva(
            modalidad = modalidad,
            tipo = tipo,
            titulo = titulo,
            descripcion = descripcion,
            reglas = reglas,
            precio = req.precio,
            calle = calle,
            numero = numero,
            colonia = colonia,
            codigoPostal = codigoPostal,
            latitud = req.latitud,
            longitud = req.longitud,
            superficie = superficie,
            inmueble = inmueble,
            usoSuelo = usoSuelo
        )
    }

    private fun Int?.requerido(nombre: String, rango: IntRange, errores: MutableList<String>): Int {
        if (this == null || this !in rango) {
            errores += "$nombre debe estar entre ${rango.first} y ${rango.last}"
            return rango.first
        }
        return this
    }

    // ---------- Fotos ----------

    private fun validarFotos(fotos: List<ByteArray>, errores: MutableList<String>): List<FotoNueva> {
        if (fotos.isEmpty()) {
            errores += "Agrega al menos 1 foto"
            return emptyList()
        }
        if (fotos.size > MAX_FOTOS) {
            errores += "Máximo $MAX_FOTOS fotos por anuncio"
            return emptyList()
        }
        return fotos.mapIndexedNotNull { i, bytes ->
            val n = i + 1
            when {
                bytes.isEmpty() -> {
                    errores += "La foto $n está vacía"
                    null
                }
                bytes.size > MAX_FOTO_BYTES -> {
                    errores += "La foto $n pesa más de 2 MB"
                    null
                }
                else -> {
                    val tipo = detectarTipoImagen(bytes)
                    if (tipo == null) {
                        errores += "La foto $n debe ser JPEG, PNG o WebP"
                        null
                    } else {
                        FotoNueva(bytes, tipo)
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
        val ROLES_ADMIN = setOf(Roles.ADMINISTRADOR, Roles.SUPER_ADMINISTRADOR)
        const val ESTADO_PUBLICADA = "PUBLICADA"
        const val ESTADO_RECHAZADA = "RECHAZADA"
        val ESTADOS = setOf("EN_REVISION", ESTADO_PUBLICADA, ESTADO_RECHAZADA)
        const val MIN_MOTIVO = 10
        const val MAX_MOTIVO = 500
        const val MIN_MOTIVO_DESACTIVAR = 5
        val TIPOS = setOf("DEPARTAMENTO", "CASA", "TERRENO")
        val USOS_SUELO = setOf("HABITACIONAL", "AGRICOLA")
        val REGEX_CODIGO_POSTAL = Regex("^[0-9]{5}$")

        const val MAX_TITULO = 60
        const val MIN_DESCRIPCION = 30
        const val MAX_DESCRIPCION = 1000
        const val MAX_REGLAS = 300
        const val MAX_PRECIO = 999_999_999L
        const val MAX_SUPERFICIE = 9_999_999
        const val MAX_CALLE = 120
        const val MAX_NUMERO = 20
        const val MAX_COLONIA = 80

        const val MAX_FOTOS = MAX_FOTOS_ANUNCIO
        const val MAX_FOTO_BYTES = 2 * 1024 * 1024
        val FIRMA_PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val FIRMA_RIFF = "RIFF".toByteArray()
        val FIRMA_WEBP = "WEBP".toByteArray()
    }
}
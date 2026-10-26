package com.equipo4.nearhouse.api.publicaciones

import com.equipo4.nearhouse.api.auth.Roles
import com.equipo4.nearhouse.api.common.CuerpoInvalidoException
import com.equipo4.nearhouse.api.common.ProhibidoException
import com.equipo4.nearhouse.api.common.ValidacionException
import kotlinx.serialization.json.Json

/**
 * Reglas de negocio de publicaciones: quién puede publicar y que los datos y las fotos sean válidos.
 * Las reglas coinciden con las restricciones de la base de datos (migración 02).
 */
class PublicacionService(private val repositorio: PublicacionRepository) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun crear(
        usuarioId: Int,
        rol: String,
        datosJson: String?,
        fotos: List<ByteArray>
    ): PublicacionCreadaResponse {
        if (rol !in ROLES_PUBLICADORES) {
            throw ProhibidoException("Solo vendedores y administradores pueden publicar anuncios")
        }

        val req = leerDatos(datosJson)
        val errores = mutableListOf<String>()
        val publicacion = validarDatos(req, errores)
        val fotosValidas = validarFotos(fotos, errores)
        if (errores.isNotEmpty()) throw ValidacionException(errores)

        return repositorio.crear(usuarioId, publicacion, fotosValidas)
    }

    private fun leerDatos(datosJson: String?): CrearPublicacionRequest {
        if (datosJson.isNullOrBlank()) throw CuerpoInvalidoException()
        return try {
            json.decodeFromString<CrearPublicacionRequest>(datosJson)
        } catch (e: IllegalArgumentException) {   // incluye SerializationException
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
        // La base exige superficie > 0 en toda propiedad, pero la app solo la pide en terreno.
        // En casa y departamento se guarda este valor de relleno (los m² van en la descripción).
        var superficie = SUPERFICIE_NO_CAPTURADA

        when (tipo) {
            "TERRENO" -> {
                superficie = req.superficie ?: 0
                if (superficie !in 1..MAX_SUPERFICIE) errores += "La superficie del terreno debe ser mayor que 0"
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
                    // Mascotas solo existe al rentar
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
                    // Se revisan los primeros bytes: no basta con confiar en el tipo que declara el archivo
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
        val ROLES_PUBLICADORES = setOf(Roles.VENDEDOR, Roles.ADMINISTRADOR, Roles.SUPER_ADMINISTRADOR)
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
        const val SUPERFICIE_NO_CAPTURADA = 1

        const val MAX_FOTOS = 5
        const val MAX_FOTO_BYTES = 2 * 1024 * 1024
        val FIRMA_PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val FIRMA_RIFF = "RIFF".toByteArray()
        val FIRMA_WEBP = "WEBP".toByteArray()
    }
}
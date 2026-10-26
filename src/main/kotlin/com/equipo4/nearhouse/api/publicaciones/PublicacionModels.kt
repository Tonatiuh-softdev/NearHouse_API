package com.equipo4.nearhouse.api.publicaciones

import kotlinx.serialization.Serializable

// ---------- Lo que la app MANDA (campo "datos" del multipart) ----------

/**
 * Datos del anuncio. Las fotos van aparte, como archivos en el campo "fotos".
 * La colonia llega como TEXTO (la que detectó el Geocoder): la base la crea sola si no existe.
 */
@Serializable
data class CrearPublicacionRequest(
    val titulo: String,
    val descripcion: String,
    val reglas: String? = null,
    val precio: Long,
    val modalidad: String,            // "Venta" o "Renta"
    val tipo: String,                 // DEPARTAMENTO, CASA o TERRENO
    val calle: String,
    val numero: String,               // puede ser "S/N"
    val colonia: String,
    val codigoPostal: String? = null,
    val latitud: Double,
    val longitud: Double,
    val superficie: Int? = null,      // solo se pide en TERRENO
    val dormitorios: Int? = null,
    val banos: Int? = null,
    val cochera: Int? = null,
    val piso: Int? = null,            // solo DEPARTAMENTO (0 = planta baja)
    val niveles: Int? = null,         // solo CASA
    val amueblado: Boolean = false,   // solo DEPARTAMENTO
    val mascotas: Boolean = false,    // solo al rentar
    val elevador: Boolean = false,    // solo DEPARTAMENTO
    val patio: Boolean = false,       // solo CASA (jardín/patio)
    val usoSuelo: String? = null      // solo TERRENO: HABITACIONAL o AGRICOLA
)

// ---------- Lo que la API RESPONDE ----------

@Serializable
data class PublicacionCreadaResponse(
    val id: Int,
    val titulo: String,
    val estado: String,
    val fotos: List<Int>
)

// ---------- Modelos internos (ya validados y limpios) ----------

data class DatosInmueble(
    val dormitorios: Int,
    val banos: Int,
    val cochera: Int,
    val piso: Int?,
    val niveles: Int?,
    val amueblado: Boolean,
    val mascotas: Boolean,
    val elevador: Boolean,
    val patio: Boolean
)

data class PublicacionNueva(
    val modalidad: String,
    val tipo: String,
    val titulo: String,
    val descripcion: String,
    val reglas: String?,
    val precio: Long,
    val calle: String,
    val numero: String,
    val colonia: String,
    val codigoPostal: String?,
    val latitud: Double,
    val longitud: Double,
    val superficie: Int,
    val inmueble: DatosInmueble?,   // null en TERRENO
    val usoSuelo: String?           // solo en TERRENO
)

class FotoNueva(val bytes: ByteArray, val tipo: String)
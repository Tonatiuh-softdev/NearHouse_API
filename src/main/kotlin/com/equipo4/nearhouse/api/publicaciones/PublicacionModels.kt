package com.equipo4.nearhouse.api.publicaciones

import kotlinx.serialization.Serializable

/** Máximo de fotos por anuncio. Cambia solo este número si quieres otro límite. */
const val MAX_FOTOS_ANUNCIO = 20

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
    val usoSuelo: String? = null,     // solo TERRENO: HABITACIONAL o AGRICOLA
    // ---- Solo al editar ----
    /** Ids de las fotos actuales que se quitan. */
    val fotosEliminar: List<Int> = emptyList(),
    /** True = la primera foto nueva reemplaza a la portada actual. */
    val reemplazaPortada: Boolean = false
)

/** Pausar (false) o reactivar (true) un anuncio. */
@Serializable
data class ActivaRequest(val activa: Boolean)

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

// ---------- Consulta de anuncios (home y administrador) ----------

/**
 * Datos del vendedor que se mandan con cada anuncio.
 * [tieneFoto] y [fotoVersion] permiten mostrar su foto sin cargar los bytes aquí:
 * la imagen se pide a GET /api/v1/usuarios/{id}/foto, y [fotoVersion] cambia cuando sube otra.
 */
@Serializable
data class VendedorResponse(
    val id: Int,
    val nombre: String,
    val correo: String,
    val telefono: String,
    val tieneFoto: Boolean,
    val fotoVersion: Long?
)

/** Datos de la revisión del administrador (solo si el anuncio ya fue revisado). */
@Serializable
data class RevisionResponse(
    val fecha: Long,
    val revisor: String?,
    val motivo: String?
)

/**
 * Anuncio completo. Lo usan el home (solo anuncios PUBLICADA) y el administrador (todos).
 * Las fotos van como ids: la imagen se pide a GET /api/v1/fotos/{id}.
 * [superficie] solo viene en TERRENO (en casa y departamento va en la descripción).
 */
@Serializable
data class PublicacionResponse(
    val id: Int,
    val titulo: String,
    val descripcion: String,
    val reglas: String?,
    val precio: Double,
    val modalidad: String,            // "Venta" o "Renta"
    val tipo: String,                 // DEPARTAMENTO, CASA o TERRENO
    val estado: String,               // EN_REVISION, PUBLICADA o RECHAZADA
    val activa: Boolean,              // false = el dueño la dio de baja
    val calle: String,
    val numero: String,
    val colonia: String,
    val codigoPostal: String?,
    val latitud: Double,
    val longitud: Double,
    val superficie: Int?,
    val usoSuelo: String?,
    val dormitorios: Int?,
    val banos: Int?,
    val cochera: Int?,
    val piso: Int?,
    val niveles: Int?,
    val amueblado: Boolean,
    val mascotas: Boolean,
    val elevador: Boolean,
    val patio: Boolean,
    val fotos: List<Int>,
    val vendedor: VendedorResponse,
    val fechaRegistro: Long,
    val revision: RevisionResponse?,
    val guardada: Boolean = false
)

@Serializable
data class RechazarRequest(val motivo: String)

class FotoGuardada(val bytes: ByteArray, val tipo: String)

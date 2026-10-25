package com.equipo4.nearhouse.api.publicaciones

import kotlinx.serialization.Serializable

@Serializable data class ColoniaResponse(val id: Int, val nombre: String)
@Serializable data class PublicacionRequest(
    val titulo: String,
    val descripcion: String,
    val precio: Double,
    val modalidad: String,
    val tipo: String,
    val calle: String,
    val numero: String,
    val latitud: Double,
    val longitud: Double,
    val idColonia: Int,
    val superficie: Int,
    val codigoPostal: String? = null,
    val amueblado: Boolean = false,
    val dormitorios: Int = 0,
    val banos: Int = 0,
    val mascotas: Boolean = false,
    val cochera: Int = 0,
    val patio: Boolean = false,
    val piso: Int? = null,
    val elevador: Boolean = false,
    val niveles: Int? = null,
    val usoSuelo: String? = null,
    val reglas: String? = null
)
@Serializable data class FotoCreadaResponse(val id: Int, val orden: Int)
@Serializable data class PublicacionCreadaResponse(val id: Int, val titulo: String, val estado: String, val fotos: List<FotoCreadaResponse>)
@Serializable data class FotoIdResponse(val id: Int)
@Serializable data class MiPublicacionResponse(
    val id: Int, val titulo: String, val descripcion: String, val precio: Double,
    val modalidad: String, val tipo: String, val calle: String, val numero: String,
    val latitud: Double, val longitud: Double, val idColonia: Int, val colonia: String,
    val superficie: Int, val estado: String, val fecha: String, val fotos: List<Int>,
    val dormitorios: Int? = null, val banos: Int? = null, val cochera: Int? = null,
    val piso: Int? = null, val elevador: Boolean? = null, val niveles: Int? = null,
    val usoSuelo: String? = null
)

data class FotoEntrada(val bytes: ByteArray, val contentType: String)

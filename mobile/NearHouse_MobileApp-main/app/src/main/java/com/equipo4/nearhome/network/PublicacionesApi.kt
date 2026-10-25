package com.equipo4.nearhome.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.Part
import retrofit2.http.POST
import retrofit2.http.Path

 data class ColoniaDto(val id: Int, val nombre: String)
 data class FotoCreadaDto(val id: Int, val orden: Int)
 data class PublicacionCreadaDto(val id: Int, val titulo: String, val estado: String, val fotos: List<FotoCreadaDto>)
 data class MiPublicacionDto(
    val id: Int, val titulo: String, val descripcion: String, val precio: Double,
    val modalidad: String, val tipo: String, val calle: String, val numero: String,
    val latitud: Double, val longitud: Double, val idColonia: Int, val colonia: String,
    val superficie: Int, val estado: String, val fecha: String, val fotos: List<Int>,
    val dormitorios: Int? = null, val banos: Int? = null, val cochera: Int? = null,
    val piso: Int? = null, val elevador: Boolean? = null, val niveles: Int? = null,
    val usoSuelo: String? = null
 )
 data class CrearPublicacionDto(
    val titulo: String, val descripcion: String, val precio: Double, val modalidad: String,
    val tipo: String, val calle: String, val numero: String, val latitud: Double,
    val longitud: Double, val idColonia: Int, val superficie: Int, val codigoPostal: String?,
    val amueblado: Boolean, val dormitorios: Int, val banos: Int, val mascotas: Boolean,
    val cochera: Int, val patio: Boolean, val piso: Int?, val elevador: Boolean,
    val niveles: Int?, val usoSuelo: String?, val reglas: String?
 )

interface PublicacionesApi {
    @GET("api/v1/colonias") suspend fun colonias(): Response<List<ColoniaDto>>
    @Multipart @POST("api/v1/publicaciones")
    suspend fun crear(@Part("datos") datos: RequestBody, @Part fotos: List<MultipartBody.Part>): Response<PublicacionCreadaDto>
    @GET("api/v1/publicaciones/mias") suspend fun mias(): Response<List<MiPublicacionDto>>
}

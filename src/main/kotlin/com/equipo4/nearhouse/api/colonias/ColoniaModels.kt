package com.equipo4.nearhouse.api.colonias

import kotlinx.serialization.Serializable

// ---------- Lo que la API RESPONDE ----------

/** Una colonia del catálogo del filtro "Ubicación". Solo salen colonias con anuncios publicados. */
@Serializable
data class ColoniaResponse(
    val id: Int,
    val nombre: String,
    val totalAnuncios: Int
)
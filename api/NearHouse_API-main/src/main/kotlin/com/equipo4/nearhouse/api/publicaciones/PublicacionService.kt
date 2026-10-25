package com.equipo4.nearhouse.api.publicaciones

import com.equipo4.nearhouse.api.auth.Roles
import com.equipo4.nearhouse.api.common.ProhibidoException

class PublicacionService(private val repository: PublicacionRepository) {
    suspend fun colonias() = repository.colonias()
    suspend fun crear(usuarioId: Int, rol: String, request: PublicacionRequest, fotos: List<FotoEntrada>): PublicacionCreadaResponse {
        if (rol !in setOf(Roles.VENDEDOR, Roles.ADMINISTRADOR, Roles.SUPER_ADMINISTRADOR)) {
            throw ProhibidoException("Solo Vendedor, Administrador y Super Administrador pueden publicar anuncios")
        }
        return repository.crear(usuarioId, request, fotos)
    }
    suspend fun mias(usuarioId: Int) = repository.mias(usuarioId)
    suspend fun foto(id: Int) = repository.foto(id)
}

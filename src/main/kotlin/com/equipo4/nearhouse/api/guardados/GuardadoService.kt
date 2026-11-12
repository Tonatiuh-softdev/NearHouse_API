package com.equipo4.nearhouse.api.guardados

import com.equipo4.nearhouse.api.common.NoEncontradoException
import com.equipo4.nearhouse.api.publicaciones.PublicacionResponse

class GuardadoService(private val repositorio: GuardadoRepository) {

    suspend fun guardar(idUsuario: Int, idPublicacion: Int) {
        val ok = repositorio.guardar(idUsuario, idPublicacion)
        if (!ok) {
            throw NoEncontradoException("Publicación no encontrada o no visible")
        }
    }

    suspend fun eliminar(idUsuario: Int, idPublicacion: Int) {
        repositorio.eliminar(idUsuario, idPublicacion)
    }

    suspend fun listar(idUsuario: Int, pagina: Int): List<PublicacionResponse> {
        val pag = if (pagina < 1) 1 else pagina
        return repositorio.listar(idUsuario, pag)
    }

    suspend fun obtenerIdsGuardados(idUsuario: Int): Set<Int> {
        return repositorio.obtenerIdsGuardados(idUsuario)
    }
}

package com.equipo4.nearhouse.api.admin.usuarios

import com.equipo4.nearhouse.api.auth.Roles
import com.equipo4.nearhouse.api.common.NoEncontradoException
import com.equipo4.nearhouse.api.common.ProhibidoException
import com.equipo4.nearhouse.api.common.ValidacionException
import com.equipo4.nearhouse.api.usuarios.FotoPerfil

/**
 * Reglas del panel de usuarios: quién puede entrar, y que los filtros y la página sean válidos.
 */
class AdminUsuarioService(private val repositorio: AdminUsuarioRepository) {

    suspend fun listar(
        rol: String,
        buscar: String?,
        rolTexto: String?,
        estatusTexto: String?,
        paginaTexto: String?
    ): ListaUsuariosResponse {
        exigirAdmin(rol)

        val errores = mutableListOf<String>()

        val texto = buscar?.trim()?.takeIf { it.isNotEmpty() }
        if (texto != null && texto.length > MAX_BUSQUEDA) {
            errores += "La búsqueda no puede pasar de $MAX_BUSQUEDA caracteres"
        }

        val rolFiltro = rolTexto?.trim()?.takeIf { it.isNotEmpty() }?.let { valor ->
            RolUsuario.entries.firstOrNull { it.name.equals(valor, ignoreCase = true) } ?: run {
                errores += "El rol debe ser COMPRADOR, VENDEDOR, ADMINISTRADOR o SUPER_ADMINISTRADOR"
                null
            }
        }

        val estatusFiltro = estatusTexto?.trim()?.takeIf { it.isNotEmpty() }?.let { valor ->
            EstatusUsuario.entries.firstOrNull { it.name.equals(valor, ignoreCase = true) } ?: run {
                errores += "El estatus debe ser ACTIVO, SUSPENDIDO o DESACTIVADO"
                null
            }
        }

        val pagina: Int = if (paginaTexto.isNullOrBlank()) {
            1
        } else {
            paginaTexto.trim().toIntOrNull()?.takeIf { it in 1..MAX_PAGINA }
                ?: throw ValidacionException(listOf("La página debe ser un número entre 1 y $MAX_PAGINA"))
        }

        if (errores.isNotEmpty()) throw ValidacionException(errores)

        val filtros = FiltrosUsuarios(buscar = texto, rol = rolFiltro, estatus = estatusFiltro)
        return ListaUsuariosResponse(
            totalCuentas = repositorio.contarTodas(),
            totalResultados = repositorio.contarFiltradas(filtros),
            pagina = pagina,
            porPagina = POR_PAGINA,
            usuarios = repositorio.listarFiltradas(filtros, pagina, POR_PAGINA)
        )
    }

    suspend fun detalle(rol: String, id: Int): UsuarioDetalleResponse {
        exigirAdmin(rol)
        return repositorio.detalle(id) ?: throw NoEncontradoException("El usuario no existe")
    }

    suspend fun foto(rol: String, id: Int): FotoPerfil {
        exigirAdmin(rol)
        return repositorio.buscarFoto(id) ?: throw NoEncontradoException("El usuario no tiene foto de perfil")
    }

    private fun exigirAdmin(rol: String) {
        if (rol !in ROLES_ADMIN) {
            throw ProhibidoException("Solo administradores pueden consultar usuarios")
        }
    }

    private companion object {
        const val POR_PAGINA = 20
        const val MAX_PAGINA = 10_000
        const val MAX_BUSQUEDA = 100
        val ROLES_ADMIN = setOf(Roles.ADMINISTRADOR, Roles.SUPER_ADMINISTRADOR)
    }
}
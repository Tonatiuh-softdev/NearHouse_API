package com.equipo4.nearhouse.api.admin.usuarios

import com.equipo4.nearhouse.api.auth.Roles
import com.equipo4.nearhouse.api.common.ConflictoException
import com.equipo4.nearhouse.api.common.NoEncontradoException
import com.equipo4.nearhouse.api.common.ProhibidoException
import com.equipo4.nearhouse.api.common.ValidacionException
import com.equipo4.nearhouse.api.usuarios.FotoPerfil

/**
 * Reglas del panel de usuarios: quién puede entrar, a quién puede ver y administrar,
 * y que los filtros y la página sean válidos.
 *
 * Nadie ve ni administra cuentas de su mismo nivel (ni la suya):
 * - Administrador: compradores y vendedores.
 * - Superadministrador: compradores, vendedores y administradores.
 * La cuenta del superadministrador no aparece para nadie.
 */
class AdminUsuarioService(private val repositorio: AdminUsuarioRepository) {

    suspend fun listar(
        rol: String,
        buscar: String?,
        rolTexto: String?,
        estatusTexto: String?,
        paginaTexto: String?
    ): ListaUsuariosResponse {
        val visibles = rolesVisibles(rol)

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
        if (rolFiltro != null && rolFiltro !in visibles) {
            throw ProhibidoException("No puedes consultar cuentas con ese rol")
        }

        val filtros = FiltrosUsuarios(
            buscar = texto, rol = rolFiltro, estatus = estatusFiltro, rolesVisibles = visibles
        )
        return ListaUsuariosResponse(
            totalCuentas = repositorio.contarTodas(visibles),
            totalResultados = repositorio.contarFiltradas(filtros),
            pagina = pagina,
            porPagina = POR_PAGINA,
            usuarios = repositorio.listarFiltradas(filtros, pagina, POR_PAGINA)
        )
    }

    /** Una cuenta que no puede ver (su mismo nivel o superior) responde como si no existiera. */
    suspend fun detalle(rol: String, id: Int): UsuarioDetalleResponse {
        val visibles = rolesVisibles(rol)
        val detalle = repositorio.detalle(id) ?: throw NoEncontradoException("El usuario no existe")
        if (detalle.rol !in visibles) throw NoEncontradoException("El usuario no existe")
        return detalle
    }

    suspend fun foto(rol: String, id: Int): FotoPerfil {
        exigirAdmin(rol)
        return repositorio.buscarFoto(id) ?: throw NoEncontradoException("El usuario no tiene foto de perfil")
    }

    /** Suspende una cuenta. No se puede suspender a uno mismo ni a alguien de su mismo nivel o superior. */
    suspend fun suspender(rol: String, idAdmin: Int, idUsuario: Int, req: SuspenderUsuarioRequest) {
        val visibles = rolesVisibles(rol)
        if (idAdmin == idUsuario) throw ProhibidoException("No puedes suspender tu propia cuenta")

        val motivo = req.motivo.trim()
        val errores = mutableListOf<String>()
        if (motivo.isEmpty() || motivo.length > 300) errores += "El motivo debe tener entre 1 y 300 caracteres"
        if (req.dias != null && req.dias !in 1..365) errores += "Los días van de 1 a 365, o vacío para indefinida"
        if (errores.isNotEmpty()) throw ValidacionException(errores)

        val objetivo = repositorio.estatusDe(idUsuario) ?: throw NoEncontradoException("El usuario no existe")
        exigirAdministrable(visibles, idUsuario)
        if (objetivo == "SUSPENDIDO") throw ConflictoException("Esta cuenta ya está suspendida")

        repositorio.suspender(idAdmin, idUsuario, req.dias, motivo)
    }

    /** Quita la suspensión de una cuenta. */
    suspend fun levantarSuspension(rol: String, idUsuario: Int) {
        val visibles = rolesVisibles(rol)
        val objetivo = repositorio.estatusDe(idUsuario) ?: throw NoEncontradoException("El usuario no existe")
        exigirAdministrable(visibles, idUsuario)
        if (objetivo != "SUSPENDIDO") throw ConflictoException("Esta cuenta no está suspendida")
        repositorio.levantarSuspension(idUsuario)
    }

    /** Borra la cuenta y sus datos. No se puede eliminar a uno mismo ni a alguien de su mismo nivel o superior. */
    suspend fun eliminar(rol: String, idAdmin: Int, idUsuario: Int) {
        val visibles = rolesVisibles(rol)
        if (idAdmin == idUsuario) throw ProhibidoException("No puedes eliminar tu propia cuenta")

        repositorio.estatusDe(idUsuario) ?: throw NoEncontradoException("El usuario no existe")
        exigirAdministrable(visibles, idUsuario)
        repositorio.eliminar(idUsuario)
    }

    /** Roles que puede ver y administrar quien entra al panel. Si no es administrador, 403. */
    private fun rolesVisibles(rol: String): Set<RolUsuario> = when (rol) {
        Roles.SUPER_ADMINISTRADOR -> setOf(RolUsuario.COMPRADOR, RolUsuario.VENDEDOR, RolUsuario.ADMINISTRADOR)
        Roles.ADMINISTRADOR -> setOf(RolUsuario.COMPRADOR, RolUsuario.VENDEDOR)
        else -> throw ProhibidoException("Solo administradores pueden consultar usuarios")
    }

    /** La cuenta debe ser de un rol que quien la administra puede ver. */
    private suspend fun exigirAdministrable(visibles: Set<RolUsuario>, idUsuario: Int) {
        val rolObjetivo = repositorio.rolDe(idUsuario)
            ?.let { nombre -> RolUsuario.entries.firstOrNull { it.nombreBd == nombre } }
        if (rolObjetivo == null || rolObjetivo !in visibles) {
            throw ProhibidoException("No puedes administrar esta cuenta")
        }
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
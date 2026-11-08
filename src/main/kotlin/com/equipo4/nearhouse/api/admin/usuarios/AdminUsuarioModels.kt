package com.equipo4.nearhouse.api.admin.usuarios

import com.equipo4.nearhouse.api.auth.Roles
import kotlinx.serialization.Serializable

/** Roles que se pueden filtrar y que se muestran en el panel. [nombreBd] es el texto de la tabla rol. */
enum class RolUsuario(val nombreBd: String) {
    COMPRADOR(Roles.COMPRADOR),
    VENDEDOR(Roles.VENDEDOR),
    ADMINISTRADOR(Roles.ADMINISTRADOR),
    SUPER_ADMINISTRADOR(Roles.SUPER_ADMINISTRADOR);

    companion object {
        fun desdeBd(nombre: String): RolUsuario =
            entries.firstOrNull { it.nombreBd == nombre }
                ?: error("Rol desconocido en la base de datos: $nombre")
    }
}

/** Estatus calculado por la API (ver expresionEstatusUsuario). No es una columna. */
enum class EstatusUsuario { ACTIVO, SUSPENDIDO, DESACTIVADO }

/** Filtros ya validados. Un null significa "sin filtro". */
data class FiltrosUsuarios(
    val buscar: String?,
    val rol: RolUsuario?,
    val estatus: EstatusUsuario?
)

@Serializable
data class ListaUsuariosResponse(
    val totalCuentas: Int,
    val totalResultados: Int,
    val pagina: Int,
    val porPagina: Int,
    val usuarios: List<UsuarioListaItem>
)

@Serializable
data class UsuarioListaItem(
    val id: Int,
    val nombre: String,
    val correo: String,
    val rol: RolUsuario,
    val estatus: EstatusUsuario,
    val tieneFoto: Boolean
)

@Serializable
data class UsuarioDetalleResponse(
    val id: Int,
    val nombre: String,
    val primerApellido: String,
    val segundoApellido: String?,
    val correo: String,
    val telefono: String,
    val rol: RolUsuario,
    val estatus: EstatusUsuario,
    val fechaRegistro: String,
    val plan: PlanUsuarioAdmin?,
    val publicaciones: ConteoPublicaciones,
    val reportesPendientes: Int
)

@Serializable
data class PlanUsuarioAdmin(
    val nombre: String,
    val vencimiento: String?
)

@Serializable
data class ConteoPublicaciones(
    val enRevision: Int,
    val publicadas: Int,
    val rechazadas: Int
)

@Serializable
data class SuspenderUsuarioRequest(
    /** 1 a 365 días. Vacío = indefinida (hasta que un administrador la reactive). */
    val dias: Int? = null,
    val motivo: String
)
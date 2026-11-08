package com.equipo4.nearhouse.api.admin.usuarios

import com.equipo4.nearhouse.api.usuarios.FotoPerfil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.OffsetDateTime
import javax.sql.DataSource

/**
 * ÚNICA fuente del estatus de una cuenta. La lista, el filtro por estatus y el detalle usan esta expresión.
 * Cuando cambie la regla de suspensiones, solo se modifica aquí.
 *
 * - SUSPENDIDO: un administrador la suspendió y la suspensión sigue vigente (las indefinidas también).
 * - DESACTIVADO: el usuario desactivó su cuenta o pidió eliminarla.
 * - ACTIVO: las demás. Si está suspendida y además desactivada, gana SUSPENDIDO.
 */
internal fun expresionEstatusUsuario(): String = """
    CASE
        WHEN u.fh_suspension_hasta_usuario > CURRENT_TIMESTAMP THEN 'SUSPENDIDO'
        WHEN u.fh_desactivacion_usuario IS NOT NULL THEN 'DESACTIVADO'
        ELSE 'ACTIVO'
    END
""".trimIndent()

/** Un reporte es pendiente mientras no lo atienda un administrador. Confirma el nombre de la columna (ver notas). */
private const val SQL_REPORTE_PENDIENTE = "rp.t_estado_reporte = 'PENDIENTE'"

/**
 * Consultas del panel de usuarios. Nunca selecciona contraseña, Google ID, versión de sesión,
 * intentos fallidos ni bloqueos: solo las columnas que se muestran.
 */
class AdminUsuarioRepository(private val dataSource: DataSource) {

    suspend fun contarTodas(): Int = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM usuario").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
    }

    suspend fun contarFiltradas(filtros: FiltrosUsuarios): Int = withContext(Dispatchers.IO) {
        val (where, valores) = condiciones(filtros)
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM usuario u JOIN rol r ON r.pke_id_rol = u.fke_id_rol$where")
                .use { ps ->
                    ps.asignar(valores)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
        }
    }

    suspend fun listarFiltradas(filtros: FiltrosUsuarios, pagina: Int, porPagina: Int): List<UsuarioListaItem> =
        withContext(Dispatchers.IO) {
            val (where, valores) = condiciones(filtros)
            val sql = """
                SELECT u.pke_id_usuario, u.t_nombre_usuario, u.t_primer_apellido_usuario,
                       u.t_segundo_apellido_usuario, u.t_correo_usuario, r.t_nombre_rol,
                       (u.by_foto_usuario IS NOT NULL) AS tiene_foto,
                       ${expresionEstatusUsuario()} AS estatus
                FROM usuario u
                JOIN rol r ON r.pke_id_rol = u.fke_id_rol$where
                ORDER BY u.fh_fecha_registro_usuario DESC, u.pke_id_usuario DESC
                LIMIT ? OFFSET ?
            """.trimIndent()
            dataSource.connection.use { conn ->
                conn.prepareStatement(sql).use { ps ->
                    ps.asignar(valores + porPagina + (pagina - 1) * porPagina)
                    ps.executeQuery().use { rs ->
                        buildList<UsuarioListaItem> {
                            while (rs.next()) {
                                add(
                                    UsuarioListaItem(
                                        id = rs.getInt("pke_id_usuario"),
                                        nombre = nombreCompleto(
                                            rs.getString("t_nombre_usuario"),
                                            rs.getString("t_primer_apellido_usuario"),
                                            rs.getString("t_segundo_apellido_usuario")
                                        ),
                                        correo = rs.getString("t_correo_usuario"),
                                        rol = RolUsuario.desdeBd(rs.getString("t_nombre_rol")),
                                        estatus = EstatusUsuario.valueOf(rs.getString("estatus")),
                                        tieneFoto = rs.getBoolean("tiene_foto")
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

    /** Detalle de una cuenta, o null si no existe. */
    suspend fun detalle(id: Int): UsuarioDetalleResponse? = withContext(Dispatchers.IO) {
        val sql = """
            SELECT u.pke_id_usuario, u.t_nombre_usuario, u.t_primer_apellido_usuario,
                   u.t_segundo_apellido_usuario, u.t_correo_usuario, u.t_telefono_usuario,
                   u.fh_fecha_registro_usuario, r.t_nombre_rol,
                   ${expresionEstatusUsuario()} AS estatus,
                   s.t_nombre_suscripcion, u.fh_vencimiento_suscripcion_usuario,
                   (SELECT COUNT(*) FROM publicacion p
                     WHERE p.fke_id_usuario = u.pke_id_usuario AND p.t_estado_publicacion = 'EN_REVISION') AS en_revision,
                   (SELECT COUNT(*) FROM publicacion p
                     WHERE p.fke_id_usuario = u.pke_id_usuario AND p.t_estado_publicacion = 'PUBLICADA') AS publicadas,
                   (SELECT COUNT(*) FROM publicacion p
                     WHERE p.fke_id_usuario = u.pke_id_usuario AND p.t_estado_publicacion = 'RECHAZADA') AS rechazadas,
                   (SELECT COUNT(*) FROM reporte rp
                     JOIN publicacion p ON p.pke_id_publicacion = rp.fke_id_publicacion
                     WHERE p.fke_id_usuario = u.pke_id_usuario AND $SQL_REPORTE_PENDIENTE) AS reportes_pendientes
            FROM usuario u
            JOIN rol r ON r.pke_id_rol = u.fke_id_rol
            LEFT JOIN suscripcion s ON s.pke_id_suscripcion = u.fke_id_suscripcion
            WHERE u.pke_id_usuario = ?
        """.trimIndent()
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) mapearDetalle(rs) else null }
            }
        }
    }

    /** Foto de perfil de la cuenta, o null si no tiene. */
    suspend fun buscarFoto(id: Int): FotoPerfil? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT by_foto_usuario, t_tipo_foto_usuario FROM usuario WHERE pke_id_usuario = ? AND by_foto_usuario IS NOT NULL"
            ).use { ps ->
                ps.setInt(1, id)
                ps.executeQuery().use { rs ->
                    if (rs.next()) FotoPerfil(rs.getBytes(1), rs.getString(2)) else null
                }
            }
        }
    }

    /** Suspende la cuenta con fn_suspender_usuario (migración 11). Los días vacíos = indefinida. */
    suspend fun suspender(idAdmin: Int, idUsuario: Int, dias: Int?, motivo: String): Unit = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT fn_suspender_usuario(?, ?, ?, ?)").use { ps ->
                ps.setInt(1, idUsuario)
                ps.setInt(2, idAdmin)
                if (dias == null) ps.setNull(3, Types.INTEGER) else ps.setInt(3, dias)
                ps.setString(4, motivo)
                ps.execute()
            }
        }
    }

    /** Quita la suspensión en este momento con fn_levantar_suspension (migración 11). */
    suspend fun levantarSuspension(idUsuario: Int): Unit = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT fn_levantar_suspension(?)").use { ps ->
                ps.setInt(1, idUsuario)
                ps.execute()
            }
        }
    }

    /** Borra la cuenta con sus publicaciones, fotos y reportes (fn_eliminar_usuario, migración 08). */
    suspend fun eliminar(idUsuario: Int): Unit = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT fn_eliminar_usuario(?)").use { ps ->
                ps.setInt(1, idUsuario)
                ps.execute()
            }
        }
    }

    /** Estatus actual de la cuenta, para validar antes de actuar. Null si no existe. */
    suspend fun estatusDe(idUsuario: Int): String? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT estado FROM vw_usuario_estado WHERE id_usuario = ?").use { ps ->
                ps.setInt(1, idUsuario)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }
    }

    /** Rol de la cuenta (texto de la tabla rol). Null si no existe. */
    suspend fun rolDe(idUsuario: Int): String? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT r.t_nombre_rol FROM usuario u JOIN rol r ON r.pke_id_rol = u.fke_id_rol WHERE u.pke_id_usuario = ?"
            ).use { ps ->
                ps.setInt(1, idUsuario)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }
    }

    // ---------- Auxiliares ----------

    /** Arma el WHERE con los filtros. Todo valor va como parámetro (?), nunca concatenado. */
    private fun condiciones(filtros: FiltrosUsuarios): Pair<String, List<Any>> {
        val clausulas = mutableListOf<String>()
        val valores = mutableListOf<Any>()

        filtros.buscar?.let { texto ->
            clausulas += """
                (LOWER(CONCAT_WS(' ', u.t_nombre_usuario, u.t_primer_apellido_usuario, u.t_segundo_apellido_usuario)) LIKE ? ESCAPE '!'
                 OR LOWER(u.t_correo_usuario) LIKE ? ESCAPE '!'
                 OR LOWER(u.t_telefono_usuario) LIKE ? ESCAPE '!')
            """.trimIndent()
            val patron = patronLike(texto)
            repeat(3) { valores += patron }
        }
        filtros.rol?.let { rol ->
            clausulas += "r.t_nombre_rol = ?"
            valores += rol.nombreBd
        }
        filtros.estatus?.let { estatus ->
            clausulas += "${expresionEstatusUsuario()} = ?"
            valores += estatus.name
        }

        val where = if (clausulas.isEmpty()) "" else " WHERE " + clausulas.joinToString(" AND ")
        return where to valores
    }

    /** Patrón para LIKE. Escapa % y _ para que la búsqueda los tome como texto normal. */
    private fun patronLike(texto: String): String {
        val escapado = texto.lowercase()
            .replace("!", "!!")
            .replace("%", "!%")
            .replace("_", "!_")
        return "%$escapado%"
    }

    private fun PreparedStatement.asignar(valores: List<Any>) {
        valores.forEachIndexed { i, valor ->
            when (valor) {
                is Int -> setInt(i + 1, valor)
                else -> setString(i + 1, valor.toString())
            }
        }
    }

    private fun mapearDetalle(rs: ResultSet): UsuarioDetalleResponse {
        val nombrePlan = rs.getString("t_nombre_suscripcion")
        val plan = nombrePlan?.let {
            PlanUsuarioAdmin(
                nombre = it,
                vencimiento = rs.getObject("fh_vencimiento_suscripcion_usuario", OffsetDateTime::class.java)?.toString()
            )
        }
        return UsuarioDetalleResponse(
            id = rs.getInt("pke_id_usuario"),
            nombre = rs.getString("t_nombre_usuario"),
            primerApellido = rs.getString("t_primer_apellido_usuario"),
            segundoApellido = rs.getString("t_segundo_apellido_usuario"),
            correo = rs.getString("t_correo_usuario"),
            telefono = rs.getString("t_telefono_usuario"),
            rol = RolUsuario.desdeBd(rs.getString("t_nombre_rol")),
            estatus = EstatusUsuario.valueOf(rs.getString("estatus")),
            fechaRegistro = rs.getObject("fh_fecha_registro_usuario", OffsetDateTime::class.java).toString(),
            plan = plan,
            publicaciones = ConteoPublicaciones(
                enRevision = rs.getInt("en_revision"),
                publicadas = rs.getInt("publicadas"),
                rechazadas = rs.getInt("rechazadas")
            ),
            reportesPendientes = rs.getInt("reportes_pendientes")
        )
    }

    private fun nombreCompleto(nombre: String, primer: String, segundo: String?): String =
        listOfNotNull(nombre, primer, segundo?.takeIf { it.isNotBlank() }).joinToString(" ")
}
/** ola. */
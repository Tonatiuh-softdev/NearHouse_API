package com.equipo4.nearhouse.api.notificaciones

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.sql.ResultSet
import java.time.OffsetDateTime
import javax.sql.DataSource

class NotificacionRepository(private val dataSource: DataSource) {

    /** Guarda un aviso y regresa su id. */
    suspend fun crear(n: NuevaNotificacion): Int = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO notificacion (
                    fke_id_usuario, t_tipo_notificacion, jb_datos_notificacion,
                    fke_id_publicacion, fke_id_reporte
                ) VALUES (?, ?, ?::jsonb, ?, ?)
                RETURNING pke_id_notificacion
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, n.usuarioId)
                ps.setString(2, n.tipo.name)
                ps.setString(3, n.datosJson)
                if (n.idPublicacion == null) ps.setNull(4, java.sql.Types.INTEGER) else ps.setInt(4, n.idPublicacion)
                if (n.idReporte == null) ps.setNull(5, java.sql.Types.INTEGER) else ps.setInt(5, n.idReporte)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
    }

    /** Notificaciones del usuario, de la más nueva a la más vieja, de [porPagina] en [porPagina]. */
    suspend fun listar(usuarioId: Int, pagina: Int, porPagina: Int): List<NotificacionResponse> =
        withContext(Dispatchers.IO) {
            val sql = """
                SELECT n.pke_id_notificacion, n.t_tipo_notificacion, n.jb_datos_notificacion::text AS datos,
                       n.b_leida_notificacion, n.fh_fecha_registro_notificacion,
                       n.fke_id_publicacion, n.fke_id_reporte,
                       (SELECT f.pke_id_foto FROM foto f
                         WHERE f.fke_id_propiedad = pr.pke_id_propiedad
                         ORDER BY f.e_orden_visibilidad_foto LIMIT 1) AS foto_portada
                FROM notificacion n
                LEFT JOIN propiedad pr ON pr.fke_id_publicacion = n.fke_id_publicacion
                WHERE n.fke_id_usuario = ?
                ORDER BY n.fh_fecha_registro_notificacion DESC, n.pke_id_notificacion DESC
                LIMIT ? OFFSET ?
            """.trimIndent()
            dataSource.connection.use { conn ->
                conn.prepareStatement(sql).use { ps ->
                    ps.setInt(1, usuarioId)
                    ps.setInt(2, porPagina)
                    ps.setInt(3, (pagina - 1) * porPagina)
                    ps.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(rs.toNotificacion()) }
                    }
                }
            }
        }

    suspend fun contarNoLeidas(usuarioId: Int): Int = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM notificacion WHERE fke_id_usuario = ? AND NOT b_leida_notificacion"
            ).use { ps ->
                ps.setInt(1, usuarioId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
    }

    /** Marca una como leída. Regresa false si no es de este usuario o no existe. */
    suspend fun marcarLeida(usuarioId: Int, id: Int): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                UPDATE notificacion
                SET b_leida_notificacion = TRUE, fh_leida_notificacion = CURRENT_TIMESTAMP
                WHERE pke_id_notificacion = ? AND fke_id_usuario = ? AND NOT b_leida_notificacion
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, id)
                ps.setInt(2, usuarioId)
                ps.executeUpdate() > 0
            }
        }
    }

    /** Marca todas las del usuario como leídas. Regresa cuántas cambió. */
    suspend fun marcarTodasLeidas(usuarioId: Int): Int = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                UPDATE notificacion
                SET b_leida_notificacion = TRUE, fh_leida_notificacion = CURRENT_TIMESTAMP
                WHERE fke_id_usuario = ? AND NOT b_leida_notificacion
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, usuarioId)
                ps.executeUpdate()
            }
        }
    }

    /** Borra una notificación. Regresa false si no es de este usuario o no existe. */
    suspend fun borrar(usuarioId: Int, id: Int): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "DELETE FROM notificacion WHERE pke_id_notificacion = ? AND fke_id_usuario = ?"
            ).use { ps ->
                ps.setInt(1, id)
                ps.setInt(2, usuarioId)
                ps.executeUpdate() > 0
            }
        }
    }

    /** Borra todas las del usuario. */
    suspend fun borrarTodas(usuarioId: Int): Int = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM notificacion WHERE fke_id_usuario = ?").use { ps ->
                ps.setInt(1, usuarioId)
                ps.executeUpdate()
            }
        }
    }

    /** Corre la limpieza diaria (migración 12). Regresa cuántas notificaciones borró. */
    suspend fun limpiar(): Int = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT fn_limpiar_notificaciones()").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
    }

    /** Si la notificación existe y es de este usuario (leída o no). */
    suspend fun existeDeUsuario(usuarioId: Int, id: Int): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT 1 FROM notificacion WHERE pke_id_notificacion = ? AND fke_id_usuario = ?"
            ).use { ps ->
                ps.setInt(1, id)
                ps.setInt(2, usuarioId)
                ps.executeQuery().use { it.next() }
            }
        }
}

    private fun ResultSet.toNotificacion(): NotificacionResponse {
        val datos: JsonElement = Json.parseToJsonElement(getString("datos"))
        val fotoPortada = getInt("foto_portada").takeIf { !wasNull() }
        val idPublicacion = getInt("fke_id_publicacion").takeIf { !wasNull() }
        val idReporte = getInt("fke_id_reporte").takeIf { !wasNull() }
        return NotificacionResponse(
            id = getInt("pke_id_notificacion"),
            tipo = TipoNotificacion.valueOf(getString("t_tipo_notificacion")),
            datos = datos,
            leida = getBoolean("b_leida_notificacion"),
            fecha = getObject("fh_fecha_registro_notificacion", OffsetDateTime::class.java).toInstant().toEpochMilli(),
            idPublicacion = idPublicacion,
            idReporte = idReporte,
            idFotoPublicacion = fotoPortada
        )
    }
}
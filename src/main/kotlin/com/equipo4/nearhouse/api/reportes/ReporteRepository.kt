package com.equipo4.nearhouse.api.reportes

import com.equipo4.nearhouse.api.common.ConflictoException
import com.equipo4.nearhouse.api.common.ValidacionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/**
 * Única capa que habla con PostgreSQL para reportes.
 * El reporte y sus evidencias se guardan en UNA transacción.
 */
class ReporteRepository(private val dataSource: DataSource) {

    /** La publicación solo si está PUBLICADA y activa (la misma regla que el home). */
    suspend fun buscarVisible(publicacionId: Int): PublicacionVisible? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT fke_id_usuario FROM publicacion
                WHERE pke_id_publicacion = ? AND t_estado_publicacion = 'PUBLICADA' AND b_estatus_publicacion
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, publicacionId)
                ps.executeQuery().use { rs -> if (rs.next()) PublicacionVisible(rs.getInt(1)) else null }
            }
        }
    }

    suspend fun existePendiente(publicacionId: Int, usuarioId: Int): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT 1 FROM reporte
                WHERE fke_id_publicacion = ? AND fke_id_usuario_registro = ? AND t_estado_reporte = 'PENDIENTE'
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, publicacionId)
                ps.setInt(2, usuarioId)
                ps.executeQuery().use { rs -> rs.next() }
            }
        }
    }

    /** Reportes que el usuario hizo en las últimas 24 horas (de cualquier estado). */
    suspend fun reportesUltimas24h(usuarioId: Int): Int = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT COUNT(*) FROM reporte
                WHERE fke_id_usuario_registro = ? AND fh_fecha_registro_reporte >= now() - interval '24 hours'
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, usuarioId)
                ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
        }
    }

    suspend fun crear(
        usuarioId: Int,
        publicacionId: Int,
        categoria: String,
        motivo: String,
        descripcion: String,
        fotos: List<FotoEvidencia>
    ): ReporteCreadoResponse = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val (reporteId, estado) = insertarReporte(conn, usuarioId, publicacionId, categoria, motivo, descripcion)
                // El orden 1 es la primera imagen que mandó el usuario
                fotos.forEachIndexed { i, foto -> insertarEvidencia(conn, reporteId, i + 1, foto) }
                conn.commit()
                ReporteCreadoResponse(id = reporteId, estado = estado)
            } catch (e: Exception) {
                conn.rollback()
                throw traducirError(e)
            } finally {
                conn.autoCommit = true
            }
        }
    }

    private fun insertarReporte(
        conn: Connection,
        usuarioId: Int,
        publicacionId: Int,
        categoria: String,
        motivo: String,
        descripcion: String
    ): Pair<Int, String> =
        conn.prepareStatement(
            """
            INSERT INTO reporte (
                fke_id_publicacion, fke_id_usuario_registro,
                t_categoria_reporte, t_motivo_reporte, t_descripcion_reporte
            ) VALUES (?, ?, ?, ?, ?)
            RETURNING pke_id_reporte, t_estado_reporte
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, publicacionId)
            ps.setInt(2, usuarioId)
            ps.setString(3, categoria)
            ps.setString(4, motivo)
            ps.setString(5, descripcion)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt("pke_id_reporte") to rs.getString("t_estado_reporte")
            }
        }

    private fun insertarEvidencia(conn: Connection, reporteId: Int, orden: Int, foto: FotoEvidencia) {
        conn.prepareStatement(
            """
            INSERT INTO evidencia (fke_id_reporte, e_orden_evidencia, by_imagen_evidencia, t_tipo_imagen_evidencia)
            VALUES (?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, reporteId)
            ps.setInt(2, orden)
            ps.setBytes(3, foto.bytes)
            ps.setString(4, foto.tipo)
            ps.executeUpdate()
        }
    }

    /**
     * Si dos peticiones llegan al mismo tiempo, la base rechaza la segunda por el índice único
     * uq_reporte_pendiente_por_usuario. Ese caso se responde como 409.
     */
    private fun traducirError(e: Exception): Exception {
        if (e !is SQLException) return e
        val restriccion = (e as? PSQLException)?.serverErrorMessage?.constraint
        return when {
            e.sqlState == "23505" && restriccion == "uq_reporte_pendiente_por_usuario" ->
                ConflictoException("Ya reportaste esta publicación; está en revisión")
            e.sqlState == "23514" ->
                ValidacionException(listOf("Los datos del reporte no cumplen las reglas de la base de datos"))
            else -> e
        }
    }
}
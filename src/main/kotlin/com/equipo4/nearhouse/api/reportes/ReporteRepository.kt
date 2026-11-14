package com.equipo4.nearhouse.api.reportes

import com.equipo4.nearhouse.api.common.ConflictoException
import com.equipo4.nearhouse.api.common.NoEncontradoException
import com.equipo4.nearhouse.api.common.ProhibidoException
import com.equipo4.nearhouse.api.common.ValidacionException
import com.equipo4.nearhouse.api.publicaciones.FotoGuardada
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.OffsetDateTime
import javax.sql.DataSource

/**
 * Única capa que habla con PostgreSQL para reportes (de publicaciones y de usuarios).
 * El reporte y sus evidencias se guardan en UNA transacción.
 */
class ReporteRepository(private val dataSource: DataSource) {

    /** La publicación solo si está PUBLICADA y activa (la misma regla que el home). */
    suspend fun buscarVisible(publicacionId: Int): PublicacionVisible? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT v.fke_id_usuario, p.t_nombre_publicacion
                FROM vw_publicacion_visible v
                JOIN publicacion p ON p.pke_id_publicacion = v.pke_id_publicacion
                WHERE v.pke_id_publicacion = ?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, publicacionId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) PublicacionVisible(rs.getInt(1), rs.getString(2)) else null
                }
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

    /** La cuenta solo si se puede ver: no desactivada ni con eliminación programada. */
    suspend fun buscarUsuarioReportable(usuarioId: Int): UsuarioReportable? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT u.pke_id_usuario, u.t_nombre_usuario, u.t_primer_apellido_usuario, r.t_nombre_rol
                FROM usuario u
                JOIN rol r ON r.pke_id_rol = u.fke_id_rol
                WHERE u.pke_id_usuario = ?
                  AND u.fh_desactivacion_usuario IS NULL
                  AND u.fh_eliminacion_programada_usuario IS NULL
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, usuarioId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        UsuarioReportable(
                            id = rs.getInt(1),
                            nombre = listOfNotNull(rs.getString(2), rs.getString(3)).joinToString(" "),
                            rol = rs.getString(4)
                        )
                    } else {
                        null
                    }
                }
            }
        }
    }

    suspend fun existePendienteUsuario(reportadoId: Int, usuarioId: Int): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT 1 FROM reporte
                WHERE fke_id_usuario_reportado = ? AND fke_id_usuario_registro = ? AND t_estado_reporte = 'PENDIENTE'
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, reportadoId)
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
        objetivo: ObjetivoReporte,
        categoria: String,
        motivo: String,
        descripcion: String,
        fotos: List<FotoEvidencia>
    ): ReporteCreadoResponse = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val (reporteId, estado) = insertarReporte(conn, usuarioId, objetivo, categoria, motivo, descripcion)
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
        objetivo: ObjetivoReporte,
        categoria: String,
        motivo: String,
        descripcion: String
    ): Pair<Int, String> =
        conn.prepareStatement(
            """
            INSERT INTO reporte (
                fke_id_publicacion, fke_id_usuario_reportado, fke_id_usuario_registro,
                t_categoria_reporte, t_motivo_reporte, t_descripcion_reporte
            ) VALUES (?, ?, ?, ?, ?, ?)
            RETURNING pke_id_reporte, t_estado_reporte
            """.trimIndent()
        ).use { ps ->
            // Solo uno de los dos lleva valor (ck_reporte_objetivo)
            when (objetivo) {
                is ObjetivoReporte.Publicacion -> {
                    ps.setInt(1, objetivo.id)
                    ps.setNull(2, Types.INTEGER)
                }
                is ObjetivoReporte.Usuario -> {
                    ps.setNull(1, Types.INTEGER)
                    ps.setInt(2, objetivo.id)
                }
            }
            ps.setInt(3, usuarioId)
            ps.setString(4, categoria)
            ps.setString(5, motivo)
            ps.setString(6, descripcion)
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
            e.sqlState == "23505" && restriccion == "uq_reporte_usuario_pendiente" ->
                ConflictoException("Ya reportaste a este usuario; está en revisión")
            e.sqlState == "23514" ->
                ValidacionException(listOf("Los datos del reporte no cumplen las reglas de la base de datos"))
            else -> e
        }
    }

    // ---------- Administrador ----------

    suspend fun listarAdmin(estado: String, pagina: Int): List<ReporteListaResponse> = withContext(Dispatchers.IO) {
        // Pendientes: el más viejo primero. Resueltos: el más nuevo primero.
        val orden = if (estado == "PENDIENTE") "ASC" else "DESC"
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "$SELECT_REPORTE WHERE r.t_estado_reporte = ? ORDER BY r.fh_fecha_registro_reporte $orden LIMIT 20 OFFSET ?"
            ).use { ps ->
                ps.setString(1, estado)
                ps.setInt(2, (pagina - 1) * 20)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toListaItem()) } }
            }
        }
    }

    suspend fun detalleAdmin(id: Int): ReporteDetalleResponse? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("$SELECT_REPORTE WHERE r.pke_id_reporte = ?").use { ps ->
                ps.setInt(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toDetalle() else null }
            }
        }
    }

    suspend fun buscarEvidencia(id: Int): FotoGuardada? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT by_imagen_evidencia, t_tipo_imagen_evidencia FROM evidencia WHERE pke_id_captura = ?"
            ).use { ps ->
                ps.setInt(1, id)
                ps.executeQuery().use { rs ->
                    if (rs.next()) FotoGuardada(rs.getBytes(1), rs.getString(2)) else null
                }
            }
        }
    }

    /**
     * Quién reportó y qué se reportó (publicación con su dueño, o la cuenta): lo que hace falta para avisar al resolver.
     * En reportes de usuario, el "título" del aviso es el nombre de la cuenta.
     */
    suspend fun datosParaAviso(reporteId: Int): AvisoReporte? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT r.fke_id_usuario_registro, p.fke_id_usuario, p.pke_id_publicacion, r.fke_id_usuario_reportado,
                       COALESCE(p.t_nombre_publicacion,
                                concat_ws(' ', ur.t_nombre_usuario, ur.t_primer_apellido_usuario)) AS titulo
                FROM reporte r
                LEFT JOIN publicacion p ON p.pke_id_publicacion = r.fke_id_publicacion
                LEFT JOIN usuario ur ON ur.pke_id_usuario = r.fke_id_usuario_reportado
                WHERE r.pke_id_reporte = ?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, reporteId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        AvisoReporte(
                            reportanteId = rs.intONull(1),
                            duenoId = rs.intONull(2),
                            publicacionId = rs.intONull(3),
                            usuarioReportadoId = rs.intONull(4),
                            titulo = rs.getString(5).orEmpty()
                        )
                    } else {
                        null
                    }
                }
            }
        }
    }

    /**
     * Resuelve un reporte pendiente. Si [retirar] es true, la publicación pasa a RECHAZADA
     * y los demás reportes pendientes de esa publicación se cierran como PROCEDENTE. Todo en una transacción.
     * Retirar solo aplica a reportes de publicación.
     *
     * El orden de las validaciones importa: primero quién lo resuelve (403) y después el estado (409).
     * Un administrador no resuelve reportes de sus publicaciones, sobre su cuenta ni los que él mismo hizo.
     */
    suspend fun resolver(id: Int, revisorId: Int, estado: String, nota: String, retirar: Boolean): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    val actual = conn.prepareStatement(
                        """
                        SELECT r.fke_id_publicacion, p.fke_id_usuario, r.fke_id_usuario_reportado,
                               r.fke_id_usuario_registro, r.t_estado_reporte
                        FROM reporte r
                        LEFT JOIN publicacion p ON p.pke_id_publicacion = r.fke_id_publicacion
                        WHERE r.pke_id_reporte = ?
                        FOR UPDATE OF r
                        """.trimIndent()
                    ).use { ps ->
                        ps.setInt(1, id)
                        ps.executeQuery().use { rs ->
                            if (!rs.next()) throw NoEncontradoException("El reporte no existe")
                            ReporteParaResolver(
                                publicacionId = rs.intONull(1),
                                duenoId = rs.intONull(2),
                                usuarioReportadoId = rs.intONull(3),
                                reportanteId = rs.intONull(4),
                                estado = rs.getString(5)
                            )
                        }
                    }

                    if (actual.duenoId == revisorId) {
                        throw ProhibidoException("No puedes resolver reportes de tus propias publicaciones")
                    }
                    if (actual.usuarioReportadoId == revisorId) {
                        throw ProhibidoException("No puedes resolver reportes sobre tu propia cuenta")
                    }
                    if (actual.reportanteId == revisorId) {
                        throw ProhibidoException("No puedes resolver reportes que tú hiciste")
                    }
                    if (actual.estado != "PENDIENTE") throw ConflictoException("Este reporte ya fue resuelto")
                    val publicacionId = actual.publicacionId
                    if (retirar && publicacionId == null) {
                        throw ValidacionException(listOf("Solo se puede retirar la publicación en reportes de publicaciones"))
                    }

                    conn.prepareStatement(
                        """
                        UPDATE reporte
                        SET t_estado_reporte = ?, t_resolucion_reporte = ?,
                            fh_fecha_actualizacion_reporte = CURRENT_TIMESTAMP,
                            fke_id_usuario_actualizacion = ?
                        WHERE pke_id_reporte = ?
                        """.trimIndent()
                    ).use { ps ->
                        ps.setString(1, estado)
                        ps.setString(2, nota)
                        ps.setInt(3, revisorId)
                        ps.setInt(4, id)
                        ps.executeUpdate()
                    }

                    if (retirar && publicacionId != null) {
                        conn.prepareStatement(
                            """
                            UPDATE publicacion
                            SET t_estado_publicacion = 'RECHAZADA',
                                t_motivo_rechazo_publicacion = ?,
                                fh_fecha_revision_publicacion = CURRENT_TIMESTAMP,
                                fke_id_usuario_revision = ?,
                                fh_fecha_actualizacion_publicacion = CURRENT_TIMESTAMP,
                                fke_id_usuario_actualizacion = ?
                            WHERE pke_id_publicacion = ?
                            """.trimIndent()
                        ).use { ps ->
                            ps.setString(1, nota)
                            ps.setInt(2, revisorId)
                            ps.setInt(3, revisorId)
                            ps.setInt(4, publicacionId)
                            ps.executeUpdate()
                        }

                        conn.prepareStatement(
                            """
                            UPDATE reporte
                            SET t_estado_reporte = 'PROCEDENTE', t_resolucion_reporte = ?,
                                fh_fecha_actualizacion_reporte = CURRENT_TIMESTAMP,
                                fke_id_usuario_actualizacion = ?
                            WHERE fke_id_publicacion = ? AND t_estado_reporte = 'PENDIENTE'
                            """.trimIndent()
                        ).use { ps ->
                            ps.setString(1, nota)
                            ps.setInt(2, revisorId)
                            ps.setInt(3, publicacionId)
                            ps.executeUpdate()
                        }
                    }
                    conn.commit()
                } catch (e: Exception) {
                    conn.rollback()
                    throw e
                } finally {
                    conn.autoCommit = true
                }
            }
        }

    // ---------- Mapeo ----------

    private fun ResultSet.millis(columna: String): Long? =
        getObject(columna, OffsetDateTime::class.java)?.toInstant()?.toEpochMilli()

    private fun ResultSet.intONull(columna: Int): Int? = getInt(columna).let { if (wasNull()) null else it }

    private fun ResultSet.intONull(columna: String): Int? = getInt(columna).let { if (wasNull()) null else it }

    /** Null en reportes de usuario. */
    private fun ResultSet.resumen(): PublicacionResumenResponse? {
        val id = intONull("pub_id") ?: return null
        return PublicacionResumenResponse(
            id = id,
            titulo = getString("pub_titulo"),
            fotoId = (getObject("foto_principal") as? Number)?.toInt(),
            estado = getString("pub_estado"),
            reportesPendientes = getInt("pendientes_pub")
        )
    }

    /** Null en reportes de publicación. */
    private fun ResultSet.usuarioReportado(): UsuarioReportadoResponse? {
        val id = intONull("ur_id") ?: return null
        return UsuarioReportadoResponse(
            id = id,
            nombre = listOfNotNull(getString("ur_nombre"), getString("ur_apellido")).joinToString(" "),
            tieneFoto = getBoolean("ur_tiene_foto"),
            reportesPendientes = getInt("pendientes_usr")
        )
    }

    private fun ResultSet.toListaItem() = ReporteListaResponse(
        id = getInt("pke_id_reporte"),
        tipo = getString("tipo"),
        categoria = getString("t_categoria_reporte"),
        motivo = getString("t_motivo_reporte"),
        descripcionCorta = getString("t_descripcion_reporte").take(80),
        fecha = millis("fh_fecha_registro_reporte") ?: 0L,
        estado = getString("t_estado_reporte"),
        imagenes = getInt("num_evidencias"),
        publicacion = resumen(),
        usuario = usuarioReportado()
    )

    private fun ResultSet.toDetalle() = ReporteDetalleResponse(
        id = getInt("pke_id_reporte"),
        tipo = getString("tipo"),
        categoria = getString("t_categoria_reporte"),
        motivo = getString("t_motivo_reporte"),
        descripcion = getString("t_descripcion_reporte"),
        fecha = millis("fh_fecha_registro_reporte") ?: 0L,
        estado = getString("t_estado_reporte"),
        imagenes = (getArray("evidencias_ids").array as Array<*>).map { (it as Number).toInt() },
        publicacion = resumen(),
        usuario = usuarioReportado(),
        // Si la cuenta de quien reportó se eliminó, el id queda vacío
        reportadoPorId = intONull("rep_id"),
        reportadoPorNombre = getString("rep_nombre")?.let { listOfNotNull(it, getString("rep_apellido")).joinToString(" ") },
        reportadoPorCorreo = getString("rep_correo"),
        nota = getString("t_resolucion_reporte"),
        resueltoPor = getString("res_nombre")?.let { listOfNotNull(it, getString("res_apellido")).joinToString(" ") },
        fechaResolucion = millis("fh_fecha_actualizacion_reporte")
    )

    /** Lo que se revisa antes de resolver un reporte. */
    private data class ReporteParaResolver(
        val publicacionId: Int?,
        val duenoId: Int?,
        val usuarioReportadoId: Int?,
        val reportanteId: Int?,
        val estado: String
    )

    private companion object {
        const val SELECT_REPORTE = """
            SELECT r.pke_id_reporte,
                   CASE WHEN r.fke_id_usuario_reportado IS NOT NULL THEN 'USUARIO' ELSE 'PUBLICACION' END AS tipo,
                   r.t_categoria_reporte, r.t_motivo_reporte, r.t_descripcion_reporte,
                   r.t_estado_reporte, r.t_resolucion_reporte,
                   r.fh_fecha_registro_reporte, r.fh_fecha_actualizacion_reporte,
                   p.pke_id_publicacion AS pub_id, p.t_nombre_publicacion AS pub_titulo,
                   p.t_estado_publicacion AS pub_estado,
                   (SELECT f.pke_id_foto FROM foto f WHERE f.fke_id_propiedad = pr.pke_id_propiedad
                      ORDER BY f.e_orden_visibilidad_foto LIMIT 1) AS foto_principal,
                   (SELECT COUNT(*) FROM evidencia e WHERE e.fke_id_reporte = r.pke_id_reporte) AS num_evidencias,
                   COALESCE((SELECT array_agg(e.pke_id_captura ORDER BY e.e_orden_evidencia)
                             FROM evidencia e WHERE e.fke_id_reporte = r.pke_id_reporte),
                            ARRAY[]::int[]) AS evidencias_ids,
                   (SELECT COUNT(*) FROM reporte r2 WHERE r2.fke_id_publicacion = p.pke_id_publicacion
                      AND r2.t_estado_reporte = 'PENDIENTE') AS pendientes_pub,
                   ur.pke_id_usuario AS ur_id, ur.t_nombre_usuario AS ur_nombre,
                   ur.t_primer_apellido_usuario AS ur_apellido, (ur.by_foto_usuario IS NOT NULL) AS ur_tiene_foto,
                   (SELECT COUNT(*) FROM reporte r3 WHERE r3.fke_id_usuario_reportado = ur.pke_id_usuario
                      AND r3.t_estado_reporte = 'PENDIENTE') AS pendientes_usr,
                   r.fke_id_usuario_registro AS rep_id,
                   u.t_nombre_usuario AS rep_nombre, u.t_primer_apellido_usuario AS rep_apellido,
                   u.t_correo_usuario AS rep_correo,
                   a.t_nombre_usuario AS res_nombre, a.t_primer_apellido_usuario AS res_apellido
            FROM reporte r
            LEFT JOIN publicacion p ON p.pke_id_publicacion = r.fke_id_publicacion
            LEFT JOIN propiedad pr ON pr.fke_id_publicacion = p.pke_id_publicacion
            LEFT JOIN usuario ur ON ur.pke_id_usuario = r.fke_id_usuario_reportado
            LEFT JOIN usuario u ON u.pke_id_usuario = r.fke_id_usuario_registro
            LEFT JOIN usuario a ON a.pke_id_usuario = r.fke_id_usuario_actualizacion
        """
    }
}
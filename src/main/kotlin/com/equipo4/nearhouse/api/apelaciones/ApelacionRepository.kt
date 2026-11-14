package com.equipo4.nearhouse.api.apelaciones

import com.equipo4.nearhouse.api.common.ConflictoException
import com.equipo4.nearhouse.api.common.NoEncontradoException
import com.equipo4.nearhouse.api.common.ProhibidoException
import com.equipo4.nearhouse.api.common.ValidacionException
import com.equipo4.nearhouse.api.publicaciones.FotoGuardada
import com.equipo4.nearhouse.api.reportes.FotoEvidencia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.OffsetDateTime
import javax.sql.DataSource

/**
 * Un anuncio fue RETIRADO POR UN REPORTE (y no rechazado en una revisión normal) cuando está RECHAZADA
 * y un reporte PROCEDENTE se cerró en el mismo momento en que se rechazó. Al retirar, la API actualiza
 * el anuncio y los reportes en una sola transacción con CURRENT_TIMESTAMP, que es la misma hora para
 * toda la transacción. Si después el dueño lo edita o un administrador lo revisa otra vez, deja de cumplirse.
 * Requiere el alias "p" para la tabla publicacion.
 */
const val SQL_RETIRADA_POR_REPORTE = """
    p.t_estado_publicacion = 'RECHAZADA'
    AND EXISTS (SELECT 1 FROM reporte r
                WHERE r.fke_id_publicacion = p.pke_id_publicacion
                  AND r.t_estado_reporte = 'PROCEDENTE'
                  AND r.fh_fecha_actualizacion_reporte = p.fh_fecha_revision_publicacion)
"""

/** Única capa que habla con PostgreSQL para apelaciones. La apelación y sus imágenes van en UNA transacción. */
class ApelacionRepository(private val dataSource: DataSource) {

    // ---------- Quien apela ----------

    /** El anuncio solo si es de [usuarioId] y está retirado por un reporte. */
    suspend fun retiroApelable(publicacionId: Int, usuarioId: Int): MedidaPublicacion? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT p.t_nombre_publicacion, p.fh_fecha_revision_publicacion,
                       p.t_motivo_rechazo_publicacion, p.fke_id_usuario_revision
                FROM publicacion p
                WHERE p.pke_id_publicacion = ? AND p.fke_id_usuario = ? AND $SQL_RETIRADA_POR_REPORTE
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, publicacionId)
                ps.setInt(2, usuarioId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        MedidaPublicacion(
                            titulo = rs.getString(1),
                            fecha = rs.getObject(2, OffsetDateTime::class.java),
                            motivo = rs.getString(3).orEmpty(),
                            adminId = rs.intONull(4)
                        )
                    } else {
                        null
                    }
                }
            }
        }
    }

    /** ¿Ya apeló el retiro actual de este anuncio? */
    suspend fun existeApelacionDelRetiro(publicacionId: Int): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT 1 FROM apelacion a
                JOIN publicacion p ON p.pke_id_publicacion = a.fke_id_publicacion
                WHERE a.fke_id_publicacion = ? AND a.t_tipo_apelacion = 'PUBLICACION'
                  AND a.fh_fecha_medida_apelacion = p.fh_fecha_revision_publicacion
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, publicacionId)
                ps.executeQuery().use { rs -> rs.next() }
            }
        }
    }

    /** Anuncios de [usuarioId] retirados por un reporte, con el estado de su apelación (null = no ha apelado). */
    suspend fun retiradasPorReporte(usuarioId: Int): Map<Int, ApelacionResumen> = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT p.pke_id_publicacion, a.t_estado_apelacion, a.t_resolucion_apelacion
                FROM publicacion p
                LEFT JOIN apelacion a ON a.t_tipo_apelacion = 'PUBLICACION'
                     AND a.fke_id_publicacion = p.pke_id_publicacion
                     AND a.fh_fecha_medida_apelacion = p.fh_fecha_revision_publicacion
                WHERE p.fke_id_usuario = ? AND $SQL_RETIRADA_POR_REPORTE
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, usuarioId)
                ps.executeQuery().use { rs ->
                    buildMap { while (rs.next()) put(rs.getInt(1), ApelacionResumen(rs.getString(2), rs.getString(3))) }
                }
            }
        }
    }

    /** Suspensión vigente de la cuenta, o null si no está suspendida. */
    suspend fun suspensionVigente(usuarioId: Int): SuspensionVigente? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT u.fh_suspension_usuario,
                       CASE WHEN u.fh_suspension_hasta_usuario = 'infinity' THEN NULL
                            ELSE u.fh_suspension_hasta_usuario END AS hasta,
                       u.t_motivo_suspension_usuario, u.fke_id_usuario_suspension
                FROM usuario u
                WHERE u.pke_id_usuario = ? AND u.fh_suspension_hasta_usuario > CURRENT_TIMESTAMP
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, usuarioId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        SuspensionVigente(
                            desde = rs.getObject(1, OffsetDateTime::class.java),
                            hasta = rs.getObject(2, OffsetDateTime::class.java),
                            motivo = rs.getString(3).orEmpty(),
                            adminId = rs.intONull(4)
                        )
                    } else {
                        null
                    }
                }
            }
        }
    }

    /** Apelación de la suspensión vigente (null si todavía no apela). */
    suspend fun apelacionDeSuspensionVigente(usuarioId: Int): ApelacionResumen? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT a.t_estado_apelacion, a.t_resolucion_apelacion
                FROM apelacion a
                JOIN usuario u ON u.pke_id_usuario = a.fke_id_usuario
                WHERE a.fke_id_usuario = ? AND a.t_tipo_apelacion = 'SUSPENSION'
                  AND a.fh_fecha_medida_apelacion = u.fh_suspension_usuario
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, usuarioId)
                ps.executeQuery().use { rs -> if (rs.next()) ApelacionResumen(rs.getString(1), rs.getString(2)) else null }
            }
        }
    }

    /**
     * Guarda la apelación de un anuncio retirado. La fecha, el motivo y el administrador de la medida
     * se copian del anuncio en la misma sentencia (así la fecha queda exacta).
     */
    suspend fun crearDePublicacion(
        usuarioId: Int,
        publicacionId: Int,
        descripcion: String,
        fotos: List<FotoEvidencia>
    ): ApelacionCreadaResponse = crear(fotos) { conn ->
        conn.prepareStatement(
            """
            INSERT INTO apelacion (
                fke_id_usuario, t_tipo_apelacion, fke_id_publicacion, fh_fecha_medida_apelacion,
                t_motivo_medida_apelacion, fke_id_usuario_medida, t_descripcion_apelacion
            )
            SELECT ?, 'PUBLICACION', p.pke_id_publicacion, p.fh_fecha_revision_publicacion,
                   p.t_motivo_rechazo_publicacion, p.fke_id_usuario_revision, ?
            FROM publicacion p
            WHERE p.pke_id_publicacion = ? AND p.fke_id_usuario = ? AND $SQL_RETIRADA_POR_REPORTE
            RETURNING pke_id_apelacion, t_estado_apelacion
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, usuarioId)
            ps.setString(2, descripcion)
            ps.setInt(3, publicacionId)
            ps.setInt(4, usuarioId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) throw NoEncontradoException("Este anuncio ya no se puede apelar")
                ApelacionCreadaResponse(rs.getInt(1), rs.getString(2))
            }
        }
    }

    /** Guarda la apelación de la suspensión vigente de [usuarioId]. */
    suspend fun crearDeSuspension(
        usuarioId: Int,
        descripcion: String,
        fotos: List<FotoEvidencia>
    ): ApelacionCreadaResponse = crear(fotos) { conn ->
        conn.prepareStatement(
            """
            INSERT INTO apelacion (
                fke_id_usuario, t_tipo_apelacion, fke_id_publicacion, fh_fecha_medida_apelacion,
                t_motivo_medida_apelacion, fke_id_usuario_medida, t_descripcion_apelacion
            )
            SELECT u.pke_id_usuario, 'SUSPENSION', NULL, u.fh_suspension_usuario,
                   u.t_motivo_suspension_usuario, u.fke_id_usuario_suspension, ?
            FROM usuario u
            WHERE u.pke_id_usuario = ? AND u.fh_suspension_hasta_usuario > CURRENT_TIMESTAMP
            RETURNING pke_id_apelacion, t_estado_apelacion
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, descripcion)
            ps.setInt(2, usuarioId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) throw NoEncontradoException("Tu cuenta ya no está suspendida")
                ApelacionCreadaResponse(rs.getInt(1), rs.getString(2))
            }
        }
    }

    private suspend fun crear(
        fotos: List<FotoEvidencia>,
        insertar: (Connection) -> ApelacionCreadaResponse
    ): ApelacionCreadaResponse = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val creada = insertar(conn)
                // El orden 1 es la primera imagen que mandó el usuario
                fotos.forEachIndexed { i, foto ->
                    conn.prepareStatement(
                        """
                        INSERT INTO evidencia_apelacion (
                            fke_id_apelacion, e_orden_evidencia_apelacion,
                            by_imagen_evidencia_apelacion, t_tipo_imagen_evidencia_apelacion
                        ) VALUES (?, ?, ?, ?)
                        """.trimIndent()
                    ).use { ps ->
                        ps.setInt(1, creada.id)
                        ps.setInt(2, i + 1)
                        ps.setBytes(3, foto.bytes)
                        ps.setString(4, foto.tipo)
                        ps.executeUpdate()
                    }
                }
                conn.commit()
                creada
            } catch (e: Exception) {
                conn.rollback()
                throw traducirError(e)
            } finally {
                conn.autoCommit = true
            }
        }
    }

    /** Dos envíos al mismo tiempo: la base rechaza el segundo por el índice único (409). */
    private fun traducirError(e: Exception): Exception {
        if (e !is SQLException) return e
        val restriccion = (e as? PSQLException)?.serverErrorMessage?.constraint
        return when {
            e.sqlState == "23505" && restriccion in setOf("uq_apelacion_publicacion", "uq_apelacion_suspension") ->
                ConflictoException("Ya apelaste esta decisión. Solo se puede apelar una vez")
            e.sqlState == "23514" ->
                ValidacionException(listOf("Los datos de la apelación no cumplen las reglas de la base de datos"))
            else -> e
        }
    }

    // ---------- Administrador ----------

    suspend fun listarAdmin(estado: String, pagina: Int): List<ApelacionListaResponse> = withContext(Dispatchers.IO) {
        // Pendientes: la más vieja primero. Resueltas: la más nueva primero.
        val orden = if (estado == "PENDIENTE") "ASC" else "DESC"
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "$SELECT_APELACION WHERE a.t_estado_apelacion = ? ORDER BY a.fh_fecha_registro_apelacion $orden LIMIT 20 OFFSET ?"
            ).use { ps ->
                ps.setString(1, estado)
                ps.setInt(2, (pagina - 1) * 20)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toListaItem()) } }
            }
        }
    }

    suspend fun detalleAdmin(id: Int): ApelacionDetalleResponse? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("$SELECT_APELACION WHERE a.pke_id_apelacion = ?").use { ps ->
                ps.setInt(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toDetalle() else null }
            }
        }
    }

    suspend fun buscarEvidencia(id: Int): FotoGuardada? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT by_imagen_evidencia_apelacion, t_tipo_imagen_evidencia_apelacion
                FROM evidencia_apelacion WHERE pke_id_evidencia_apelacion = ?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) FotoGuardada(rs.getBytes(1), rs.getString(2)) else null }
            }
        }
    }

    /**
     * Resuelve una apelación pendiente, todo en una transacción:
     * - ACEPTADA: se revierte la medida (el anuncio vuelve a PUBLICADA o se quita la suspensión),
     *   solo si sigue siendo la misma medida.
     * - RECHAZADA: la medida se mantiene.
     * Un administrador no resuelve sus propias apelaciones ni las de una medida que él tomó.
     * En suspensiones, solo si puede administrar esa cuenta ([rolesAdministrables]: nombres de la tabla rol).
     */
    suspend fun resolver(
        id: Int,
        revisorId: Int,
        rolesAdministrables: Set<String>,
        estado: String,
        nota: String
    ): AvisoApelacion = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val actual = conn.prepareStatement(
                    """
                    SELECT a.t_tipo_apelacion, a.fke_id_usuario, a.fke_id_publicacion, a.fke_id_usuario_medida,
                           a.t_estado_apelacion, r.t_nombre_rol, u.t_correo_usuario,
                           COALESCE(p.t_nombre_publicacion, '') AS titulo
                    FROM apelacion a
                    JOIN usuario u ON u.pke_id_usuario = a.fke_id_usuario
                    JOIN rol r ON r.pke_id_rol = u.fke_id_rol
                    LEFT JOIN publicacion p ON p.pke_id_publicacion = a.fke_id_publicacion
                    WHERE a.pke_id_apelacion = ?
                    FOR UPDATE OF a
                    """.trimIndent()
                ).use { ps ->
                    ps.setInt(1, id)
                    ps.executeQuery().use { rs ->
                        if (!rs.next()) throw NoEncontradoException("La apelación no existe")
                        ApelacionParaResolver(
                            tipo = rs.getString(1),
                            apelanteId = rs.getInt(2),
                            publicacionId = rs.intONull(3),
                            medidaPorId = rs.intONull(4),
                            estado = rs.getString(5),
                            rolApelante = rs.getString(6),
                            correo = rs.getString(7),
                            titulo = rs.getString(8)
                        )
                    }
                }

                if (actual.apelanteId == revisorId) {
                    throw ProhibidoException("No puedes resolver tus propias apelaciones")
                }
                if (actual.medidaPorId == revisorId) {
                    throw ProhibidoException("Esta apelación la debe revisar un administrador distinto al que tomó la decisión")
                }
                if (actual.tipo == "SUSPENSION" && actual.rolApelante !in rolesAdministrables) {
                    throw ProhibidoException("No puedes resolver apelaciones de esta cuenta")
                }
                if (actual.estado != "PENDIENTE") throw ConflictoException("Esta apelación ya fue resuelta")

                if (estado == "ACEPTADA") revertirMedida(conn, id, actual, revisorId)

                conn.prepareStatement(
                    """
                    UPDATE apelacion
                    SET t_estado_apelacion = ?, t_resolucion_apelacion = ?,
                        fke_id_usuario_resolucion = ?, fh_fecha_resolucion_apelacion = CURRENT_TIMESTAMP
                    WHERE pke_id_apelacion = ?
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, estado)
                    ps.setString(2, nota)
                    ps.setInt(3, revisorId)
                    ps.setInt(4, id)
                    ps.executeUpdate()
                }
                conn.commit()
                AvisoApelacion(
                    apelanteId = actual.apelanteId,
                    correo = actual.correo,
                    tipo = actual.tipo,
                    publicacionId = actual.publicacionId,
                    titulo = actual.titulo
                )
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    /** Deshace la medida apelada, solo si sigue siendo la misma (misma fecha de medida). */
    private fun revertirMedida(conn: Connection, apelacionId: Int, actual: ApelacionParaResolver, revisorId: Int) {
        when (actual.tipo) {
            "PUBLICACION" -> {
                val filas = conn.prepareStatement(
                    """
                    UPDATE publicacion
                    SET t_estado_publicacion = 'PUBLICADA',
                        t_motivo_rechazo_publicacion = NULL,
                        fh_fecha_revision_publicacion = CURRENT_TIMESTAMP,
                        fke_id_usuario_revision = ?,
                        fh_fecha_actualizacion_publicacion = CURRENT_TIMESTAMP,
                        fke_id_usuario_actualizacion = ?
                    WHERE pke_id_publicacion = ?
                      AND t_estado_publicacion = 'RECHAZADA'
                      AND fh_fecha_revision_publicacion =
                          (SELECT fh_fecha_medida_apelacion FROM apelacion WHERE pke_id_apelacion = ?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setInt(1, revisorId)
                    ps.setInt(2, revisorId)
                    ps.setInt(3, actual.publicacionId ?: 0)
                    ps.setInt(4, apelacionId)
                    ps.executeUpdate()
                }
                if (filas == 0) {
                    throw ConflictoException(
                        "El anuncio cambió después de la apelación (se editó o se eliminó): ya no se puede revertir. Puedes mantener la decisión"
                    )
                }
            }
            "SUSPENSION" -> {
                // Si la suspensión ya terminó o es otra, no hay nada que quitar
                conn.prepareStatement(
                    """
                    SELECT fn_levantar_suspension(u.pke_id_usuario)
                    FROM usuario u
                    WHERE u.pke_id_usuario = ?
                      AND u.fh_suspension_usuario =
                          (SELECT fh_fecha_medida_apelacion FROM apelacion WHERE pke_id_apelacion = ?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setInt(1, actual.apelanteId)
                    ps.setInt(2, apelacionId)
                    ps.execute()
                }
            }
        }
    }

    // ---------- Mapeo ----------

    private fun ResultSet.intONull(columna: Int): Int? = getInt(columna).let { if (wasNull()) null else it }

    private fun ResultSet.intONull(columna: String): Int? = getInt(columna).let { if (wasNull()) null else it }

    private fun ResultSet.millis(columna: String): Long? =
        getObject(columna, OffsetDateTime::class.java)?.toInstant()?.toEpochMilli()

    private fun ResultSet.nombre(nombre: String, apellido: String): String? =
        getString(nombre)?.let { listOfNotNull(it, getString(apellido)).joinToString(" ") }

    private fun ResultSet.apelante() = ApelanteResponse(
        id = getInt("ap_id"),
        nombre = nombre("ap_nombre", "ap_apellido").orEmpty(),
        correo = getString("ap_correo"),
        tieneFoto = getBoolean("ap_tiene_foto")
    )

    private fun ResultSet.publicacion(): ApelacionPublicacionResponse? {
        val id = intONull("pub_id") ?: return null
        return ApelacionPublicacionResponse(
            id = id,
            titulo = getString("pub_titulo"),
            fotoId = (getObject("foto_principal") as? Number)?.toInt(),
            estado = getString("pub_estado")
        )
    }

    private fun ResultSet.toListaItem() = ApelacionListaResponse(
        id = getInt("pke_id_apelacion"),
        tipo = getString("t_tipo_apelacion"),
        descripcionCorta = getString("t_descripcion_apelacion").take(80),
        fecha = millis("fh_fecha_registro_apelacion") ?: 0L,
        estado = getString("t_estado_apelacion"),
        imagenes = getInt("num_evidencias"),
        apelante = apelante(),
        publicacion = publicacion()
    )

    private fun ResultSet.toDetalle(): ApelacionDetalleResponse {
        val vigente = getBoolean("suspension_vigente")
        return ApelacionDetalleResponse(
            id = getInt("pke_id_apelacion"),
            tipo = getString("t_tipo_apelacion"),
            descripcion = getString("t_descripcion_apelacion"),
            fecha = millis("fh_fecha_registro_apelacion") ?: 0L,
            estado = getString("t_estado_apelacion"),
            imagenes = (getArray("evidencias_ids").array as Array<*>).map { (it as Number).toInt() },
            apelante = apelante(),
            publicacion = publicacion(),
            motivoMedida = getString("t_motivo_medida_apelacion"),
            fechaMedida = millis("fh_fecha_medida_apelacion") ?: 0L,
            medidaPorId = intONull("fke_id_usuario_medida"),
            medidaPorNombre = nombre("med_nombre", "med_apellido"),
            suspendidaHasta = if (vigente) millis("suspendida_hasta") else null,
            suspensionVigente = vigente,
            nota = getString("t_resolucion_apelacion"),
            resueltoPor = nombre("res_nombre", "res_apellido"),
            fechaResolucion = millis("fh_fecha_resolucion_apelacion")
        )
    }

    /** Lo que se revisa antes de resolver. */
    private data class ApelacionParaResolver(
        val tipo: String,
        val apelanteId: Int,
        val publicacionId: Int?,
        val medidaPorId: Int?,
        val estado: String,
        val rolApelante: String,
        val correo: String,
        val titulo: String
    )

    private companion object {
        const val SELECT_APELACION = """
            SELECT a.pke_id_apelacion, a.t_tipo_apelacion, a.t_descripcion_apelacion,
                   a.fh_fecha_registro_apelacion, a.t_estado_apelacion,
                   a.t_motivo_medida_apelacion, a.fh_fecha_medida_apelacion, a.fke_id_usuario_medida,
                   a.t_resolucion_apelacion, a.fh_fecha_resolucion_apelacion,
                   (SELECT COUNT(*) FROM evidencia_apelacion e WHERE e.fke_id_apelacion = a.pke_id_apelacion) AS num_evidencias,
                   COALESCE((SELECT array_agg(e.pke_id_evidencia_apelacion ORDER BY e.e_orden_evidencia_apelacion)
                             FROM evidencia_apelacion e WHERE e.fke_id_apelacion = a.pke_id_apelacion),
                            ARRAY[]::int[]) AS evidencias_ids,
                   u.pke_id_usuario AS ap_id, u.t_nombre_usuario AS ap_nombre,
                   u.t_primer_apellido_usuario AS ap_apellido, u.t_correo_usuario AS ap_correo,
                   (u.by_foto_usuario IS NOT NULL) AS ap_tiene_foto,
                   (a.t_tipo_apelacion = 'SUSPENSION'
                     AND u.fh_suspension_hasta_usuario > CURRENT_TIMESTAMP
                     AND u.fh_suspension_usuario = a.fh_fecha_medida_apelacion) AS suspension_vigente,
                   CASE WHEN u.fh_suspension_hasta_usuario = 'infinity' THEN NULL
                        ELSE u.fh_suspension_hasta_usuario END AS suspendida_hasta,
                   p.pke_id_publicacion AS pub_id, p.t_nombre_publicacion AS pub_titulo,
                   p.t_estado_publicacion AS pub_estado,
                   (SELECT f.pke_id_foto FROM foto f WHERE f.fke_id_propiedad = pr.pke_id_propiedad
                      ORDER BY f.e_orden_visibilidad_foto LIMIT 1) AS foto_principal,
                   m.t_nombre_usuario AS med_nombre, m.t_primer_apellido_usuario AS med_apellido,
                   rs.t_nombre_usuario AS res_nombre, rs.t_primer_apellido_usuario AS res_apellido
            FROM apelacion a
            JOIN usuario u ON u.pke_id_usuario = a.fke_id_usuario
            LEFT JOIN publicacion p ON p.pke_id_publicacion = a.fke_id_publicacion
            LEFT JOIN propiedad pr ON pr.fke_id_publicacion = p.pke_id_publicacion
            LEFT JOIN usuario m ON m.pke_id_usuario = a.fke_id_usuario_medida
            LEFT JOIN usuario rs ON rs.pke_id_usuario = a.fke_id_usuario_resolucion
        """
    }
}

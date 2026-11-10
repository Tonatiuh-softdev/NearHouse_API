package com.equipo4.nearhouse.api.guardados

import com.equipo4.nearhouse.api.publicaciones.PublicacionRepository
import com.equipo4.nearhouse.api.publicaciones.PublicacionResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sql.DataSource

class GuardadoRepository(
    private val dataSource: DataSource,
    private val publicacionRepository: PublicacionRepository
) {

    /** Guarda la publicación. Regresa false si la publicación no existe en la vista visible. */
    suspend fun guardar(idUsuario: Int, idPublicacion: Int): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val existeSql = "SELECT 1 FROM vw_publicacion_visible WHERE pke_id_publicacion = ?"
            val existe = conn.prepareStatement(existeSql).use { ps ->
                ps.setInt(1, idPublicacion)
                ps.executeQuery().use { rs -> rs.next() }
            }
            if (!existe) return@withContext false

            val insertSql = """
                INSERT INTO guardado (fke_id_usuario, fke_id_publicacion)
                VALUES (?, ?)
                ON CONFLICT (fke_id_usuario, fke_id_publicacion) DO NOTHING
            """
            conn.prepareStatement(insertSql).use { ps ->
                ps.setInt(1, idUsuario)
                ps.setInt(2, idPublicacion)
                ps.executeUpdate()
            }
            true
        }
    }

    /** Elimina el guardado si existía. */
    suspend fun eliminar(idUsuario: Int, idPublicacion: Int): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val deleteSql = "DELETE FROM guardado WHERE fke_id_usuario = ? AND fke_id_publicacion = ?"
            conn.prepareStatement(deleteSql).use { ps ->
                ps.setInt(1, idUsuario)
                ps.setInt(2, idPublicacion)
                ps.executeUpdate() >= 0
            }
        }
    }

    /** Lista las publicaciones guardadas visibles del usuario (20 por página). */
    suspend fun listar(idUsuario: Int, pagina: Int, limite: Int = 20): List<PublicacionResponse> = withContext(Dispatchers.IO) {
        val offset = (pagina - 1) * limite
        val idsGuardados = mutableListOf<Int>()
        dataSource.connection.use { conn ->
            val sql = """
                SELECT g.fke_id_publicacion
                FROM guardado g
                JOIN vw_publicacion_visible p ON p.pke_id_publicacion = g.fke_id_publicacion
                WHERE g.fke_id_usuario = ?
                ORDER BY g.fh_fecha_guardado DESC
                LIMIT ? OFFSET ?
            """
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, idUsuario)
                ps.setInt(2, limite)
                ps.setInt(3, offset)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        idsGuardados.add(rs.getInt("fke_id_publicacion"))
                    }
                }
            }
        }
        val idsSet = idsGuardados.toSet()
        publicacionRepository.buscarPorIds(idsGuardados, idUsuario).map { pub ->
            pub.copy(guardada = idsSet.contains(pub.id))
        }
    }

    /** Regresa el conjunto de IDs de publicaciones guardadas por el usuario. */
    suspend fun obtenerIdsGuardados(idUsuario: Int): Set<Int> = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = "SELECT fke_id_publicacion FROM guardado WHERE fke_id_usuario = ?"
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, idUsuario)
                ps.executeQuery().use { rs ->
                    buildSet {
                        while (rs.next()) {
                            add(rs.getInt("fke_id_publicacion"))
                        }
                    }
                }
            }
        }
    }
}

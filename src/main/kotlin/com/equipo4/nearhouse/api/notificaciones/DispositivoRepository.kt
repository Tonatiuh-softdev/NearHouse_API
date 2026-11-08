package com.equipo4.nearhouse.api.notificaciones

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import javax.sql.DataSource

data class Dispositivo(val token: String, val idioma: String)

@Serializable
data class RegistrarDispositivoRequest(val token: String, val idioma: String = "es")

@Serializable
data class BajaDispositivoRequest(val token: String)

class DispositivoRepository(private val dataSource: DataSource) {

    /** Un token es de un solo celular: si ya existía con otra cuenta, pasa a esta. */
    suspend fun registrar(usuarioId: Int, token: String, idioma: String) = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                INSERT INTO dispositivo (fke_id_usuario, t_token_dispositivo, t_idioma_dispositivo)
                VALUES (?, ?, ?)
                ON CONFLICT (t_token_dispositivo) DO UPDATE
                SET fke_id_usuario = EXCLUDED.fke_id_usuario,
                    t_idioma_dispositivo = EXCLUDED.t_idioma_dispositivo,
                    fh_ultimo_uso_dispositivo = CURRENT_TIMESTAMP
            """
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, usuarioId)
                ps.setString(2, token)
                ps.setString(3, idioma)
                ps.executeUpdate()
            }
        }
    }

    /** Solo borra el token si es de ese usuario. */
    suspend fun baja(usuarioId: Int, token: String) = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM dispositivo WHERE t_token_dispositivo = ? AND fke_id_usuario = ?").use { ps ->
                ps.setString(1, token)
                ps.setInt(2, usuarioId)
                ps.executeUpdate()
            }
        }
    }

    /** Para limpiar tokens que Firebase ya no reconoce. */
    suspend fun borrarToken(token: String) = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM dispositivo WHERE t_token_dispositivo = ?").use { ps ->
                ps.setString(1, token)
                ps.executeUpdate()
            }
        }
    }

    suspend fun deUsuario(usuarioId: Int): List<Dispositivo> = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT t_token_dispositivo, t_idioma_dispositivo FROM dispositivo WHERE fke_id_usuario = ?"
            ).use { ps ->
                ps.setInt(1, usuarioId)
                ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(Dispositivo(rs.getString(1), rs.getString(2))) }
                }
            }
        }
    }
}
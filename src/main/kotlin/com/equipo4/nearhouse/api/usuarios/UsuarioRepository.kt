package com.equipo4.nearhouse.api.usuarios

import com.equipo4.nearhouse.api.common.ConflictoException
import com.equipo4.nearhouse.api.common.ValidacionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.postgresql.util.PSQLException
import java.sql.ResultSet
import java.sql.SQLException
import java.time.OffsetDateTime
import javax.sql.DataSource

/**
 * Única capa que habla con PostgreSQL para la tabla `usuario`.
 * Usa consultas preparadas (los "?"), que evitan inyección SQL.
 */
class UsuarioRepository(private val dataSource: DataSource) {

    suspend fun buscarPorCorreo(correo: String): Usuario? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("$SELECT_BASE WHERE LOWER(u.t_correo_usuario) = LOWER(?)").use { ps ->
                ps.setString(1, correo)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toUsuario() else null }
            }
        }
    }

    suspend fun buscarPorId(id: Int): Usuario? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("$SELECT_BASE WHERE u.pke_id_usuario = ?").use { ps ->
                ps.setInt(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toUsuario() else null }
            }
        }
    }

    /** Busca un usuario por su identificador único de Google. */
    suspend fun buscarPorGoogleId(googleId: String): Usuario? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("$SELECT_BASE WHERE u.t_google_id_usuario = ?").use { ps ->
                ps.setString(1, googleId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toUsuario() else null }
            }
        }
    }

    /** Busca un usuario por su número de teléfono. */
    suspend fun buscarPorTelefono(telefono: String): Usuario? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("$SELECT_BASE WHERE u.t_telefono_usuario = ?").use { ps ->
                ps.setString(1, telefono)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toUsuario() else null }
            }
        }
    }

    /** Asocia el Google ID a una cuenta de correo existente (Caso B). */
    suspend fun actualizarGoogleId(idUsuario: Int, googleId: String): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = "UPDATE usuario SET t_google_id_usuario = ?, fh_fecha_actualizacion_usuario = CURRENT_TIMESTAMP WHERE pke_id_usuario = ?"
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, googleId)
                ps.setInt(2, idUsuario)
                ps.executeUpdate() > 0
            }
        }
    }

    /** Inserta el usuario y regresa su id nuevo. */
    suspend fun crear(nuevo: NuevoUsuario): Int = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(INSERT_USUARIO).use { ps ->
                ps.setString(1, nuevo.rol)
                ps.setString(2, nuevo.nombre)
                ps.setString(3, nuevo.primerApellido)
                ps.setString(4, nuevo.segundoApellido)
                ps.setString(5, nuevo.contrasenaHash)
                ps.setString(6, nuevo.correo)
                ps.setString(7, nuevo.telefono)
                ps.setString(8, nuevo.googleId)
                ps.setBoolean(9, nuevo.googleId != null || nuevo.correoVerificado)
                ps.setBoolean(10, nuevo.googleId != null || nuevo.telefonoVerificado)
                try {
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt("pke_id_usuario")
                    }
                } catch (e: SQLException) {
                    throw traducirError(e)
                }
            }
        }
    }

    /** Convierte los errores de PostgreSQL en errores entendibles para la app. */
    private fun traducirError(e: SQLException): Exception {
        val restriccion = (e as? PSQLException)?.serverErrorMessage?.constraint
        return when (e.sqlState) {
            "23505" -> when (restriccion) { // valor duplicado
                "uq_usuario_correo" -> ConflictoException("Ese correo ya está registrado")
                "uq_usuario_telefono" -> ConflictoException("Ese teléfono ya está registrado")
                "unique_google_id_usuario" -> ConflictoException("Ese ID de Google ya está registrado")
                else -> ConflictoException("El registro ya existe")
            }
            "23514" -> when (restriccion) {
                "check_auth_method" -> ValidacionException(listOf("La cuenta debe poseer una contraseña o un ID de Google"))
                else -> ValidacionException(listOf("La base de datos rechazó los datos ($restriccion)"))
            }
            "23502" -> IllegalStateException("Falta un dato obligatorio o el rol no existe en la tabla rol", e)
            else -> e
        }
    }

    private fun ResultSet.toUsuario() = Usuario(
        id = getInt("pke_id_usuario"),
        nombre = getString("t_nombre_usuario"),
        primerApellido = getString("t_primer_apellido_usuario"),
        segundoApellido = getString("t_segundo_apellido_usuario"),
        correo = getString("t_correo_usuario"),
        telefono = getString("t_telefono_usuario"),
        contrasenaHash = getString("t_contrasena_usuario"),
        googleId = getString("t_google_id_usuario"),
        rol = getString("t_nombre_rol"),
        activo = getBoolean("b_estatus_usuario"),
        correoVerificado = getBoolean("b_correo_verificado_usuario"),
        telefonoVerificado = getBoolean("b_telefono_verificado_usuario"),
        fechaRegistro = getObject("fh_fecha_registro_usuario", OffsetDateTime::class.java).toString(),
    )

    private companion object {
        const val SELECT_BASE = """
            SELECT u.pke_id_usuario, u.t_nombre_usuario, u.t_primer_apellido_usuario,
                   u.t_segundo_apellido_usuario, u.t_correo_usuario, u.t_telefono_usuario,
                   u.t_contrasena_usuario, u.t_google_id_usuario, u.b_estatus_usuario,
                   u.b_correo_verificado_usuario, u.b_telefono_verificado_usuario,
                   u.fh_fecha_registro_usuario, r.t_nombre_rol
            FROM usuario u
            JOIN rol r ON r.pke_id_rol = u.fke_id_rol
        """

        const val INSERT_USUARIO = """
            INSERT INTO usuario (
                fke_id_rol, t_nombre_usuario, t_primer_apellido_usuario, t_segundo_apellido_usuario,
                t_contrasena_usuario, t_correo_usuario, t_telefono_usuario, t_google_id_usuario,
                b_correo_verificado_usuario, b_telefono_verificado_usuario
            ) VALUES (
                (SELECT pke_id_rol FROM rol WHERE t_nombre_rol = ? AND b_estatus_rol),
                ?, ?, ?, ?, ?, ?, ?, ?, ?
            )
            RETURNING pke_id_usuario
        """
    }
}
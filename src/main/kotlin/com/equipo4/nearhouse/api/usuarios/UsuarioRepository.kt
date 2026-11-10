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

    /** Cambia nombre y apellidos. Regresa true si encontró el usuario. */
    suspend fun actualizarPerfil(
        id: Int,
        nombre: String,
        primerApellido: String,
        segundoApellido: String?
    ): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                UPDATE usuario
                SET t_nombre_usuario = ?,
                    t_primer_apellido_usuario = ?,
                    t_segundo_apellido_usuario = ?,
                    fh_fecha_actualizacion_usuario = CURRENT_TIMESTAMP
                WHERE pke_id_usuario = ?
            """
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, nombre)
                ps.setString(2, primerApellido)
                ps.setString(3, segundoApellido)
                ps.setInt(4, id)
                ps.executeUpdate() > 0
            }
        }
    }

    /** Actualiza el correo del usuario y marca b_correo_verificado_usuario = TRUE. */
    suspend fun actualizarCorreo(idUsuario: Int, correoNuevo: String): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                UPDATE usuario
                SET t_correo_usuario = ?,
                    b_correo_verificado_usuario = TRUE,
                    fh_fecha_actualizacion_usuario = CURRENT_TIMESTAMP
                WHERE pke_id_usuario = ?
            """
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, correoNuevo)
                ps.setInt(2, idUsuario)
                try {
                    ps.executeUpdate() > 0
                } catch (e: SQLException) {
                    throw traducirError(e)
                }
            }
        }
    }

    /** Desactiva la cuenta guardando la fecha actual e incrementando la versión de sesión. */
    suspend fun desactivarCuenta(idUsuario: Int): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                UPDATE usuario
                SET fh_desactivacion_usuario = CURRENT_TIMESTAMP,
                    e_version_sesion_usuario = e_version_sesion_usuario + 1,
                    fh_fecha_actualizacion_usuario = CURRENT_TIMESTAMP
                WHERE pke_id_usuario = ?
            """
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, idUsuario)
                ps.executeUpdate() > 0
            }
        }
    }

    /**
     * Reactiva una cuenta desactivada. Si estaba por eliminarse, también cancela la eliminación
     * (las dos fechas se quitan juntas: la base no permite eliminación programada sin desactivación).
     */
    suspend fun reactivarCuenta(idUsuario: Int): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                UPDATE usuario
                SET fh_desactivacion_usuario = NULL,
                    fh_eliminacion_programada_usuario = NULL,
                    fh_fecha_actualizacion_usuario = CURRENT_TIMESTAMP
                WHERE pke_id_usuario = ?
            """
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, idUsuario)
                ps.executeUpdate() > 0
            }
        }
    }

    /**
     * Programa la eliminación de la cuenta: queda desactivada desde ahora (sus anuncios dejan de verse)
     * y se borra en [dias] días. Sube la versión de sesión para cerrar todas sus sesiones.
     * Regresa la fecha en que se borrará, o null si el usuario no existe.
     */
    suspend fun programarEliminacion(idUsuario: Int, dias: Int): OffsetDateTime? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                UPDATE usuario
                SET fh_desactivacion_usuario = COALESCE(fh_desactivacion_usuario, CURRENT_TIMESTAMP),
                    fh_eliminacion_programada_usuario = CURRENT_TIMESTAMP + make_interval(days => ?),
                    e_version_sesion_usuario = e_version_sesion_usuario + 1,
                    fh_fecha_actualizacion_usuario = CURRENT_TIMESTAMP
                WHERE pke_id_usuario = ?
                RETURNING fh_eliminacion_programada_usuario
            """
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, dias)
                ps.setInt(2, idUsuario)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getObject(1, OffsetDateTime::class.java) else null
                }
            }
        }
    }

    /** Cuentas cuya fecha de eliminación ya llegó: id y correo (para mandarles el aviso final). */
    suspend fun cuentasPorEliminar(): List<Pair<Int, String>> = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                SELECT pke_id_usuario, t_correo_usuario
                FROM usuario
                WHERE fh_eliminacion_programada_usuario <= CURRENT_TIMESTAMP
            """
            conn.prepareStatement(sql).use { ps ->
                ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.getInt(1) to rs.getString(2)) }
                }
            }
        }
    }

    /**
     * Borra la cuenta de verdad con fn_eliminar_usuario (migración 08): sus anuncios, fotos y reportes,
     * y deja vacíos los datos donde aparecía en registros de otros.
     * Vuelve a revisar la fecha dentro de la misma transacción: si el usuario inició sesión y la
     * conservó en ese momento, no se borra. Regresa true si la borró.
     */
    suspend fun eliminarDefinitivamente(idUsuario: Int): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val vencida = conn.prepareStatement(
                    """
                    SELECT 1 FROM usuario
                    WHERE pke_id_usuario = ? AND fh_eliminacion_programada_usuario <= CURRENT_TIMESTAMP
                    FOR UPDATE
                    """
                ).use { ps ->
                    ps.setInt(1, idUsuario)
                    ps.executeQuery().use { it.next() }
                }
                if (vencida) {
                    conn.prepareStatement("SELECT fn_eliminar_usuario(?)").use { ps ->
                        ps.setInt(1, idUsuario)
                        ps.execute()
                    }
                }
                conn.commit()
                vencida
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    /** Registra intento fallido de contraseña o bloqueo. */
    suspend fun registrarIntentoFallido(idUsuario: Int, nuevosIntentos: Int, bloqueoHasta: OffsetDateTime?): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                UPDATE usuario
                SET e_intentos_fallidos_usuario = ?,
                    fh_bloqueo_hasta_usuario = ?,
                    fh_fecha_actualizacion_usuario = CURRENT_TIMESTAMP
                WHERE pke_id_usuario = ?
            """
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, nuevosIntentos)
                ps.setObject(2, bloqueoHasta)
                ps.setInt(3, idUsuario)
                ps.executeUpdate() > 0
            }
        }
    }

    /** Resetea los intentos fallidos a 0 y borra el bloqueo. */
    suspend fun resetearIntentosFallidos(idUsuario: Int): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                UPDATE usuario
                SET e_intentos_fallidos_usuario = 0,
                    fh_bloqueo_hasta_usuario = NULL,
                    fh_fecha_actualizacion_usuario = CURRENT_TIMESTAMP
                WHERE pke_id_usuario = ?
            """
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, idUsuario)
                ps.executeUpdate() > 0
            }
        }
    }

    /** Actualiza la contraseña, sube la versión de sesión en 1 y reinicia intentos/bloqueo. */
    suspend fun actualizarContrasena(idUsuario: Int, nuevoHash: String): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                UPDATE usuario
                SET t_contrasena_usuario = ?,
                    fh_cambio_contrasena_usuario = CURRENT_TIMESTAMP,
                    e_version_sesion_usuario = e_version_sesion_usuario + 1,
                    e_intentos_fallidos_usuario = 0,
                    fh_bloqueo_hasta_usuario = NULL,
                    fh_fecha_actualizacion_usuario = CURRENT_TIMESTAMP
                WHERE pke_id_usuario = ?
            """
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, nuevoHash)
                ps.setInt(2, idUsuario)
                ps.executeUpdate() > 0
            }
        }
    }

    /** Guarda los bytes de la foto, su tipo y la fecha. */
    suspend fun guardarFoto(id: Int, bytes: ByteArray, tipo: String): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                UPDATE usuario
                SET by_foto_usuario = ?,
                    t_tipo_foto_usuario = ?,
                    fh_fecha_foto_usuario = CURRENT_TIMESTAMP,
                    fh_fecha_actualizacion_usuario = CURRENT_TIMESTAMP
                WHERE pke_id_usuario = ?
            """
            conn.prepareStatement(sql).use { ps ->
                ps.setBytes(1, bytes)
                ps.setString(2, tipo)
                ps.setInt(3, id)
                ps.executeUpdate() > 0
            }
        }
    }

    /** Borra la foto, su tipo y su fecha. */
    suspend fun borrarFoto(id: Int): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                UPDATE usuario
                SET by_foto_usuario = NULL,
                    t_tipo_foto_usuario = NULL,
                    fh_fecha_foto_usuario = NULL,
                    fh_fecha_actualizacion_usuario = CURRENT_TIMESTAMP
                WHERE pke_id_usuario = ?
            """
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, id)
                ps.executeUpdate() > 0
            }
        }
    }

    /** Regresa la foto del usuario, o null si no tiene. */
    suspend fun buscarFoto(id: Int): FotoPerfil? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                SELECT by_foto_usuario, t_tipo_foto_usuario
                FROM usuario
                WHERE pke_id_usuario = ? AND by_foto_usuario IS NOT NULL
            """
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, id)
                ps.executeQuery().use { rs ->
                    if (rs.next()) FotoPerfil(rs.getBytes("by_foto_usuario"), rs.getString("t_tipo_foto_usuario")) else null
                }
            }
        }
    }

    /** Planes activos, del más barato al más caro. */
    suspend fun listarPlanes(): List<PlanResponse> = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(SELECT_PLANES).use { ps ->
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                PlanResponse(
                                    id = rs.getInt("pke_id_suscripcion"),
                                    nombre = rs.getString("t_nombre_suscripcion"),
                                    precio = rs.getInt("e_precio_suscripcion"),
                                    duracionDias = rs.getInt("e_duracion_dias_suscripcion"),
                                    limiteAnuncios = rs.getInt("e_limite_anuncios_suscripcion").let { if (rs.wasNull()) null else it },
                                    nivelVisibilidad = rs.getInt("e_nivel_visibilidad_suscripcion"),
                                    diasPrueba = rs.getInt("e_dias_prueba_suscripcion"),
                                    destacado = rs.getBoolean("b_destacado_suscripcion")
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    /** Guarda el plan, sus fechas, la prueba usada y el cambio de rol en una sola transacción. */
    suspend fun contratarPlan(
        usuarioId: Int,
        plan: PlanResponse,
        inicio: OffsetDateTime,
        vencimiento: OffsetDateTime,
        usarPrueba: Boolean,
        pasarAVendedor: Boolean
    ): Unit = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val sql = """
                    UPDATE usuario
                    SET fke_id_suscripcion = ?,
                        fh_inicio_suscripcion_usuario = ?,
                        fh_vencimiento_suscripcion_usuario = ?,
                        b_prueba_gratis_usada_usuario = b_prueba_gratis_usada_usuario OR ?,
                        fke_id_rol = CASE WHEN ? THEN
                            (SELECT pke_id_rol FROM rol WHERE t_nombre_rol = 'Vendedor' AND b_estatus_rol)
                            ELSE fke_id_rol END,
                        fh_fecha_actualizacion_usuario = CURRENT_TIMESTAMP
                    WHERE pke_id_usuario = ?
                """
                conn.prepareStatement(sql).use { ps ->
                    ps.setInt(1, plan.id)
                    ps.setObject(2, inicio)
                    ps.setObject(3, vencimiento)
                    ps.setBoolean(4, usarPrueba)
                    ps.setBoolean(5, pasarAVendedor)
                    ps.setInt(6, usuarioId)
                    ps.executeUpdate()
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

    /** Convierte los errores de PostgreSQL en errores entendibles para la app. */
    private fun traducirError(e: SQLException): Exception {
        val restriccion = (e as? PSQLException)?.serverErrorMessage?.constraint
        return when (e.sqlState) {
            "23505" -> when (restriccion) { // valor duplicado
                "uq_usuario_correo" -> ConflictoException("Ese correo ya está registrado")
                "uq_usuario_telefono" -> ConflictoException("Ese teléfono ya está registrado")
                "unique_google_id_usuario" -> ConflictoException("Ese ID de Google ya está registrado")
                else -> ConflictoException("Ese correo ya está registrado")
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
        activo = getBoolean("b_cuenta_activa"),
        correoVerificado = getBoolean("b_correo_verificado_usuario"),
        telefonoVerificado = getBoolean("b_telefono_verificado_usuario"),
        fechaRegistro = getObject("fh_fecha_registro_usuario", OffsetDateTime::class.java).toString(),
        tieneFoto = getBoolean("b_tiene_foto"),
        fechaFoto = getObject("fh_fecha_foto_usuario", OffsetDateTime::class.java),
        planId = getInt("fke_id_suscripcion").let { if (wasNull()) null else it },
        planNombre = getString("t_nombre_suscripcion"),
        planInicio = getObject("fh_inicio_suscripcion_usuario", OffsetDateTime::class.java),
        planVencimiento = getObject("fh_vencimiento_suscripcion_usuario", OffsetDateTime::class.java),
        pruebaUsada = getBoolean("b_prueba_gratis_usada_usuario"),
        fechaDesactivacion = getObject("fh_desactivacion_usuario", OffsetDateTime::class.java),
        fechaEliminacionProgramada = getObject("fh_eliminacion_programada_usuario", OffsetDateTime::class.java),
        versionSesion = getInt("e_version_sesion_usuario").let { if (wasNull()) 1 else it },
        intentosFallidos = getInt("e_intentos_fallidos_usuario").let { if (wasNull()) 0 else it },
        bloqueoHasta = getObject("fh_bloqueo_hasta_usuario", OffsetDateTime::class.java)
    )

    private companion object {
        // b_tiene_foto se calcula en SQL para no traer los bytes de la foto en cada consulta.
        // b_cuenta_activa: no está suspendida por un administrador. Desde la migración 11 ya no existe
        // b_estatus_usuario; la suspensión dura hasta fh_suspension_hasta_usuario (vacío = nunca suspendida).
        const val SELECT_BASE = """
            SELECT u.pke_id_usuario, u.t_nombre_usuario, u.t_primer_apellido_usuario,
                   u.t_segundo_apellido_usuario, u.t_correo_usuario, u.t_telefono_usuario,
                   u.t_contrasena_usuario, u.t_google_id_usuario,
                   (u.fh_suspension_hasta_usuario IS NULL
                    OR u.fh_suspension_hasta_usuario <= CURRENT_TIMESTAMP) AS b_cuenta_activa,
                   u.b_correo_verificado_usuario, u.b_telefono_verificado_usuario,
                   u.fh_fecha_registro_usuario, u.fh_fecha_foto_usuario,
                   (u.by_foto_usuario IS NOT NULL) AS b_tiene_foto,
                   u.fke_id_suscripcion, s.t_nombre_suscripcion,
                   u.fh_inicio_suscripcion_usuario, u.fh_vencimiento_suscripcion_usuario,
                   u.b_prueba_gratis_usada_usuario,
                   r.t_nombre_rol,
                   u.fh_desactivacion_usuario, u.fh_eliminacion_programada_usuario,
                   u.e_version_sesion_usuario, u.e_intentos_fallidos_usuario, u.fh_bloqueo_hasta_usuario
            FROM usuario u
            JOIN rol r ON r.pke_id_rol = u.fke_id_rol
            LEFT JOIN suscripcion s ON s.pke_id_suscripcion = u.fke_id_suscripcion
        """

        const val SELECT_PLANES = """
            SELECT pke_id_suscripcion, t_nombre_suscripcion, e_precio_suscripcion,
                   e_duracion_dias_suscripcion, e_limite_anuncios_suscripcion,
                   e_nivel_visibilidad_suscripcion, e_dias_prueba_suscripcion, b_destacado_suscripcion
            FROM suscripcion
            WHERE b_estatus_suscripcion = TRUE
            ORDER BY e_precio_suscripcion
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
package com.equipo4.nearhouse.api.publicaciones

import com.equipo4.nearhouse.api.common.NoEncontradoException
import com.equipo4.nearhouse.api.common.ValidacionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.postgresql.util.PSQLException
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.OffsetDateTime
import javax.sql.DataSource

/**
 * Única capa que habla con PostgreSQL para crear anuncios.
 * Todo se guarda en UNA transacción: si algo falla, no queda un anuncio a medias.
 */
class PublicacionRepository(private val dataSource: DataSource) {

    suspend fun crear(
        usuarioId: Int,
        p: PublicacionNueva,
        fotos: List<FotoNueva>
    ): PublicacionCreadaResponse = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val modalidadId = buscarModalidad(conn, p.modalidad)
                val coloniaId = obtenerColonia(conn, p.colonia)
                val (publicacionId, estado) = insertarPublicacion(conn, usuarioId, modalidadId, p)
                val propiedadId = insertarPropiedad(conn, publicacionId, coloniaId, p)
                if (p.inmueble != null) insertarInmueble(conn, propiedadId, p.inmueble)
                if (p.usoSuelo != null) insertarTerreno(conn, propiedadId, p.usoSuelo)
                // La primera foto es la portada (orden 1)
                val fotoIds = fotos.mapIndexed { i, f -> insertarFoto(conn, propiedadId, i + 1, f) }
                conn.commit()
                PublicacionCreadaResponse(id = publicacionId, titulo = p.titulo, estado = estado, fotos = fotoIds)
            } catch (e: Exception) {
                conn.rollback()
                throw if (e is SQLException) traducirError(e) else e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    // ---------- Consultas (home y administrador) ----------

    /**
     * Anuncios con todos sus datos, del más nuevo al más viejo (máximo 500).
     * [soloPublicadas] = lo que ve el público. [estado] filtra por estado de revisión.
     * [usuarioId] deja solo los anuncios de ese vendedor (Mis anuncios).
     */
    suspend fun listar(soloPublicadas: Boolean, estado: String?, usuarioId: Int? = null): List<PublicacionResponse> =
        withContext(Dispatchers.IO) {
            val filtros = buildList {
                // Lo que ve el público sale de vw_publicacion_visible (migración 08): publicado, activo
                // y de un dueño activo. Así no salen los anuncios de cuentas desactivadas, por eliminarse o suspendidas.
                if (soloPublicadas) add("EXISTS (SELECT 1 FROM vw_publicacion_visible v WHERE v.pke_id_publicacion = p.pke_id_publicacion)")
                if (estado != null) add("p.t_estado_publicacion = ?")
                if (usuarioId != null) add("p.fke_id_usuario = ?")
            }
            val where = if (filtros.isEmpty()) "" else " WHERE " + filtros.joinToString(" AND ")
            dataSource.connection.use { conn ->
                conn.prepareStatement("$SELECT_PUBLICACION$where ORDER BY p.fh_fecha_publicacion DESC LIMIT 500").use { ps ->
                    var indice = 1
                    if (estado != null) ps.setString(indice++, estado)
                    if (usuarioId != null) ps.setInt(indice, usuarioId)
                    ps.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(rs.toPublicacion()) }
                    }
                }
            }
        }

    /** Si el público puede ver el anuncio: está en vw_publicacion_visible (publicado, activo y de una cuenta activa). */
    suspend fun esVisible(id: Int): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT 1 FROM vw_publicacion_visible WHERE pke_id_publicacion = ?").use { ps ->
                ps.setInt(1, id)
                ps.executeQuery().use { it.next() }
            }
        }
    }

    suspend fun buscarPorId(id: Int): PublicacionResponse? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("$SELECT_PUBLICACION WHERE p.pke_id_publicacion = ?").use { ps ->
                ps.setInt(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toPublicacion() else null }
            }
        }
    }

    /**
     * Pasa un anuncio de EN_REVISION a [nuevoEstado] (PUBLICADA o RECHAZADA).
     * Regresa false si no existe o si ya había sido revisado.
     */
    suspend fun revisar(id: Int, revisorId: Int, nuevoEstado: String, motivo: String?): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    """
                    UPDATE publicacion
                    SET t_estado_publicacion = ?,
                        t_motivo_rechazo_publicacion = ?,
                        fh_fecha_revision_publicacion = CURRENT_TIMESTAMP,
                        fke_id_usuario_revision = ?,
                        fh_fecha_actualizacion_publicacion = CURRENT_TIMESTAMP,
                        fke_id_usuario_actualizacion = ?
                    WHERE pke_id_publicacion = ? AND t_estado_publicacion = 'EN_REVISION'
                    """
                ).use { ps ->
                    ps.setString(1, nuevoEstado)
                    ps.setString(2, motivo)
                    ps.setInt(3, revisorId)
                    ps.setInt(4, revisorId)
                    ps.setInt(5, id)
                    ps.executeUpdate() > 0
                }
            }
        }

    // ---------- Acciones del vendedor sobre sus anuncios ----------

    /** Pausa o reactiva un anuncio. Regresa false si no existe o no es de ese usuario. */
    suspend fun cambiarActiva(id: Int, usuarioId: Int, activa: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    """
                    UPDATE publicacion
                    SET b_estatus_publicacion = ?,
                        fh_fecha_actualizacion_publicacion = CURRENT_TIMESTAMP,
                        fke_id_usuario_actualizacion = ?
                    WHERE pke_id_publicacion = ? AND fke_id_usuario = ?
                    """
                ).use { ps ->
                    ps.setBoolean(1, activa)
                    ps.setInt(2, usuarioId)
                    ps.setInt(3, id)
                    ps.setInt(4, usuarioId)
                    ps.executeUpdate() > 0
                }
            }
        }

    /**
     * Borra el anuncio de verdad. La propiedad, las fotos y los reportes se borran en cascada.
     * Regresa false si no existe o no es de ese usuario.
     */
    suspend fun eliminar(id: Int, usuarioId: Int): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "DELETE FROM publicacion WHERE pke_id_publicacion = ? AND fke_id_usuario = ?"
                ).use { ps ->
                    ps.setInt(1, id)
                    ps.setInt(2, usuarioId)
                    ps.executeUpdate() > 0
                }
            }
        }

    /**
     * Reemplaza los datos de un anuncio propio y lo manda otra vez a revisión.
     * Todo en una transacción: si algo falla, el anuncio queda como estaba.
     */
    suspend fun editar(
        id: Int,
        usuarioId: Int,
        p: PublicacionNueva,
        fotosEliminar: List<Int>,
        reemplazaPortada: Boolean,
        fotosNuevas: List<FotoNueva>
    ): Unit = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val modalidadId = buscarModalidad(conn, p.modalidad)
                val coloniaId = obtenerColonia(conn, p.colonia)
                actualizarPublicacion(conn, id, usuarioId, modalidadId, p)
                val propiedadId = buscarPropiedad(conn, id)
                actualizarPropiedad(conn, propiedadId, coloniaId, p)

                // El detalle depende del tipo (inmueble o terreno): se borra el anterior y se guarda el nuevo
                borrarDetalle(conn, "inmueble", propiedadId)
                borrarDetalle(conn, "terreno", propiedadId)
                if (p.inmueble != null) insertarInmueble(conn, propiedadId, p.inmueble)
                if (p.usoSuelo != null) insertarTerreno(conn, propiedadId, p.usoSuelo)

                borrarFotos(conn, propiedadId, fotosEliminar)
                agregarFotos(conn, propiedadId, fotosNuevas, reemplazaPortada)
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw if (e is SQLException) traducirError(e) else e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    private fun actualizarPublicacion(conn: Connection, id: Int, usuarioId: Int, modalidadId: Int, p: PublicacionNueva) {
        val filas = conn.prepareStatement(
            """
            UPDATE publicacion
            SET fke_id_modalidad = ?, t_nombre_publicacion = ?, t_descripcion_publicacion = ?,
                e_precio_publicacion = ?, t_reglas_publicacion = ?,
                t_estado_publicacion = 'EN_REVISION', t_motivo_rechazo_publicacion = NULL,
                fh_fecha_revision_publicacion = NULL, fke_id_usuario_revision = NULL,
                fh_fecha_actualizacion_publicacion = CURRENT_TIMESTAMP, fke_id_usuario_actualizacion = ?
            WHERE pke_id_publicacion = ? AND fke_id_usuario = ?
            """
        ).use { ps ->
            ps.setInt(1, modalidadId)
            ps.setString(2, p.titulo)
            ps.setString(3, p.descripcion)
            ps.setBigDecimal(4, BigDecimal.valueOf(p.precio))
            ps.setString(5, p.reglas)
            ps.setInt(6, usuarioId)
            ps.setInt(7, id)
            ps.setInt(8, usuarioId)
            ps.executeUpdate()
        }
        if (filas == 0) throw NoEncontradoException("El anuncio no existe")
    }

    private fun buscarPropiedad(conn: Connection, publicacionId: Int): Int =
        conn.prepareStatement("SELECT pke_id_propiedad FROM propiedad WHERE fke_id_publicacion = ?").use { ps ->
            ps.setInt(1, publicacionId)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getInt(1) else throw NoEncontradoException("El anuncio no existe")
            }
        }

    private fun actualizarPropiedad(conn: Connection, propiedadId: Int, coloniaId: Int, p: PublicacionNueva) {
        conn.prepareStatement(
            """
            UPDATE propiedad
            SET t_tipo_propiedad = ?, t_calle_propiedad = ?, t_numero_propiedad = ?,
                n_latitud_propiedad = ?, n_longitud_propiedad = ?, fke_id_colonia = ?,
                e_superficie_propiedad = ?, t_codigo_postal_propiedad = ?
            WHERE pke_id_propiedad = ?
            """
        ).use { ps ->
            ps.setString(1, p.tipo)
            ps.setString(2, p.calle)
            ps.setString(3, p.numero)
            ps.setBigDecimal(4, BigDecimal.valueOf(p.latitud).setScale(6, RoundingMode.HALF_UP))
            ps.setBigDecimal(5, BigDecimal.valueOf(p.longitud).setScale(6, RoundingMode.HALF_UP))
            ps.setInt(6, coloniaId)
            ps.setInt(7, p.superficie)
            ps.setString(8, p.codigoPostal)
            ps.setInt(9, propiedadId)
            ps.executeUpdate()
        }
    }

    /** [tabla] siempre es "inmueble" o "terreno" (valores fijos del código, nunca datos del usuario). */
    private fun borrarDetalle(conn: Connection, tabla: String, propiedadId: Int) {
        conn.prepareStatement("DELETE FROM $tabla WHERE fke_id_propiedad = ?").use { ps ->
            ps.setInt(1, propiedadId)
            ps.executeUpdate()
        }
    }

    private fun borrarFotos(conn: Connection, propiedadId: Int, ids: List<Int>) {
        if (ids.isEmpty()) return
        conn.prepareStatement("DELETE FROM foto WHERE fke_id_propiedad = ? AND pke_id_foto = ANY(?)").use { ps ->
            ps.setInt(1, propiedadId)
            ps.setArray(2, conn.createArrayOf("integer", ids.toTypedArray<Any?>()))
            ps.executeUpdate()
        }
    }

    /**
     * Las fotos que se quedan conservan su lugar. Las nuevas ocupan lugares libres; si no se reemplaza la portada,
     * van después de la portada actual para no quitarle su lugar.
     */
    private fun agregarFotos(conn: Connection, propiedadId: Int, nuevas: List<FotoNueva>, reemplazaPortada: Boolean) {
        if (nuevas.isEmpty()) return
        val usados = conn.prepareStatement("SELECT e_orden_visibilidad_foto FROM foto WHERE fke_id_propiedad = ?").use { ps ->
            ps.setInt(1, propiedadId)
            ps.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getInt(1)) } }
        }
        val libres = (1..MAX_FOTOS_ANUNCIO).filter { it !in usados }
        val portada = usados.minOrNull()
        val destinos = if (reemplazaPortada || portada == null) libres else libres.filter { it > portada }
        if (destinos.size < nuevas.size) throw ValidacionException(listOf("No hay espacio para agregar más fotos"))
        nuevas.forEachIndexed { i, foto -> insertarFoto(conn, propiedadId, destinos[i], foto) }
    }

    suspend fun buscarFoto(id: Int): FotoGuardada? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT by_imagen_foto, t_tipo_imagen_foto FROM foto WHERE pke_id_foto = ?").use { ps ->
                ps.setInt(1, id)
                ps.executeQuery().use { rs ->
                    if (rs.next()) FotoGuardada(rs.getBytes("by_imagen_foto"), rs.getString("t_tipo_imagen_foto")) else null
                }
            }
        }
    }

    private fun ResultSet.intOrNull(columna: String): Int? = getInt(columna).let { if (wasNull()) null else it }

    private fun ResultSet.toPublicacion(): PublicacionResponse {
        val tipo = getString("t_tipo_propiedad")
        val fechaRevision = getObject("fh_fecha_revision_publicacion", OffsetDateTime::class.java)
        val fotos = (getArray("fotos").array as Array<*>).map { (it as Number).toInt() }
        val vendedor = listOfNotNull(
            getString("vendedor_nombre"), getString("vendedor_apellido1"), getString("vendedor_apellido2")
        ).joinToString(" ")
        val revisor = getString("revisor_nombre")?.let { nombre ->
            listOfNotNull(nombre, getString("revisor_apellido1")).joinToString(" ")
        }
        // wasNull() solo sirve justo después de leer la columna, por eso se revisa antes de hacer otra lectura
        val versionFotoLeida = getLong("vendedor_foto_version")
        val versionFoto = if (wasNull()) null else versionFotoLeida

        return PublicacionResponse(
            id = getInt("pke_id_publicacion"),
            titulo = getString("t_nombre_publicacion"),
            descripcion = getString("t_descripcion_publicacion"),
            reglas = getString("t_reglas_publicacion"),
            precio = getDouble("e_precio_publicacion"),
            modalidad = getString("t_nombre_modalidad"),
            tipo = tipo,
            estado = getString("t_estado_publicacion"),
            activa = getBoolean("b_estatus_publicacion"),
            calle = getString("t_calle_propiedad"),
            numero = getString("t_numero_propiedad"),
            colonia = getString("t_nombre_colonia"),
            codigoPostal = getString("t_codigo_postal_propiedad"),
            latitud = getDouble("n_latitud_propiedad"),
            longitud = getDouble("n_longitud_propiedad"),
            // Los anuncios anteriores a este cambio guardaron 1 como relleno: ese valor no se manda
            superficie = getInt("e_superficie_propiedad").takeIf { tipo == "TERRENO" || it > 1 },
            usoSuelo = getString("t_uso_suelo_terreno"),
            dormitorios = intOrNull("e_dormitorios_inmueble"),
            banos = intOrNull("e_banos_inmueble"),
            cochera = intOrNull("e_cochera_inmueble"),
            piso = intOrNull("e_piso_inmueble"),
            niveles = intOrNull("e_niveles_inmueble"),
            amueblado = getBoolean("b_amueblado_inmueble"),
            mascotas = getBoolean("b_mascotas_inmueble"),
            elevador = getBoolean("b_elevador_inmueble"),
            patio = getBoolean("b_patio_inmueble"),
            fotos = fotos,
            vendedor = VendedorResponse(
                id = getInt("fke_id_usuario"),
                nombre = vendedor,
                correo = getString("vendedor_correo"),
                telefono = getString("vendedor_telefono"),
                tieneFoto = getBoolean("vendedor_tiene_foto"),
                fotoVersion = versionFoto
            ),
            fechaRegistro = getObject("fh_fecha_publicacion", OffsetDateTime::class.java).toInstant().toEpochMilli(),
            revision = fechaRevision?.let {
                RevisionResponse(
                    fecha = it.toInstant().toEpochMilli(),
                    revisor = revisor,
                    motivo = getString("t_motivo_rechazo_publicacion")
                )
            }
        )
    }

    private fun buscarModalidad(conn: Connection, nombre: String): Int =
        conn.prepareStatement(
            "SELECT pke_id_modalidad FROM modalidad WHERE LOWER(t_nombre_modalidad) = LOWER(?) AND b_estatus_modalidad"
        ).use { ps ->
            ps.setString(1, nombre)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getInt(1)
                else throw ValidacionException(listOf("La modalidad \"$nombre\" no existe o no está activa"))
            }
        }

    /** fn_obtener_colonia (migración 03): regresa el id y, si la colonia no existe, la agrega sola. */
    private fun obtenerColonia(conn: Connection, nombre: String): Int {
        val id = conn.prepareStatement("SELECT fn_obtener_colonia(?)").use { ps ->
            ps.setString(1, nombre)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
        val activa = conn.prepareStatement("SELECT b_estatus_colonia FROM colonia WHERE pke_id_colonia = ?").use { ps ->
            ps.setInt(1, id)
            ps.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }
        if (!activa) throw ValidacionException(listOf("La colonia \"$nombre\" no está disponible para publicar"))
        return id
    }

    private fun insertarPublicacion(
        conn: Connection,
        usuarioId: Int,
        modalidadId: Int,
        p: PublicacionNueva
    ): Pair<Int, String> =
        conn.prepareStatement(
            """
            INSERT INTO publicacion (
                fke_id_usuario, fke_id_modalidad, t_nombre_publicacion, t_descripcion_publicacion,
                e_precio_publicacion, t_reglas_publicacion, fke_id_usuario_registro
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING pke_id_publicacion, t_estado_publicacion
            """
        ).use { ps ->
            ps.setInt(1, usuarioId)
            ps.setInt(2, modalidadId)
            ps.setString(3, p.titulo)
            ps.setString(4, p.descripcion)
            ps.setBigDecimal(5, BigDecimal.valueOf(p.precio))
            ps.setString(6, p.reglas)
            ps.setInt(7, usuarioId)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt("pke_id_publicacion") to rs.getString("t_estado_publicacion")
            }
        }

    private fun insertarPropiedad(conn: Connection, publicacionId: Int, coloniaId: Int, p: PublicacionNueva): Int =
        conn.prepareStatement(
            """
            INSERT INTO propiedad (
                fke_id_publicacion, t_tipo_propiedad, t_calle_propiedad, t_numero_propiedad,
                n_latitud_propiedad, n_longitud_propiedad, fke_id_colonia,
                e_superficie_propiedad, t_codigo_postal_propiedad
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING pke_id_propiedad
            """
        ).use { ps ->
            ps.setInt(1, publicacionId)
            ps.setString(2, p.tipo)
            ps.setString(3, p.calle)
            ps.setString(4, p.numero)
            ps.setBigDecimal(5, BigDecimal.valueOf(p.latitud).setScale(6, RoundingMode.HALF_UP))
            ps.setBigDecimal(6, BigDecimal.valueOf(p.longitud).setScale(6, RoundingMode.HALF_UP))
            ps.setInt(7, coloniaId)
            ps.setInt(8, p.superficie)
            ps.setString(9, p.codigoPostal)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    private fun insertarInmueble(conn: Connection, propiedadId: Int, d: DatosInmueble) {
        conn.prepareStatement(
            """
            INSERT INTO inmueble (
                fke_id_propiedad, b_amueblado_inmueble, e_dormitorios_inmueble, e_banos_inmueble,
                b_mascotas_inmueble, e_cochera_inmueble, b_patio_inmueble, e_piso_inmueble,
                b_elevador_inmueble, e_niveles_inmueble
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """
        ).use { ps ->
            ps.setInt(1, propiedadId)
            ps.setBoolean(2, d.amueblado)
            ps.setInt(3, d.dormitorios)
            ps.setInt(4, d.banos)
            ps.setBoolean(5, d.mascotas)
            ps.setInt(6, d.cochera)
            ps.setBoolean(7, d.patio)
            ps.setIntOrNull(8, d.piso)
            ps.setBoolean(9, d.elevador)
            ps.setIntOrNull(10, d.niveles)
            ps.executeUpdate()
        }
    }

    private fun insertarTerreno(conn: Connection, propiedadId: Int, usoSuelo: String) {
        conn.prepareStatement("INSERT INTO terreno (fke_id_propiedad, t_uso_suelo_terreno) VALUES (?, ?)").use { ps ->
            ps.setInt(1, propiedadId)
            ps.setString(2, usoSuelo)
            ps.executeUpdate()
        }
    }

    private fun insertarFoto(conn: Connection, propiedadId: Int, orden: Int, foto: FotoNueva): Int =
        conn.prepareStatement(
            """
            INSERT INTO foto (fke_id_propiedad, e_orden_visibilidad_foto, by_imagen_foto, t_tipo_imagen_foto)
            VALUES (?, ?, ?, ?)
            RETURNING pke_id_foto
            """
        ).use { ps ->
            ps.setInt(1, propiedadId)
            ps.setInt(2, orden)
            ps.setBytes(3, foto.bytes)
            ps.setString(4, foto.tipo)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    private fun java.sql.PreparedStatement.setIntOrNull(index: Int, value: Int?) {
        if (value == null) setNull(index, Types.INTEGER) else setInt(index, value)
    }

    /** Convierte los errores de PostgreSQL en mensajes entendibles para la app. */
    private fun traducirError(e: SQLException): Exception {
        val restriccion = (e as? PSQLException)?.serverErrorMessage?.constraint
        return when (e.sqlState) {
            "23514" -> ValidacionException(
                listOf(
                    when (restriccion) {
                        "ck_publicacion_titulo" -> "El título debe tener entre 1 y 60 caracteres"
                        "ck_publicacion_descripcion" -> "La descripción debe tener entre 30 y 1000 caracteres"
                        "ck_publicacion_reglas" -> "Las reglas no pueden pasar de 300 caracteres"
                        "ck_propiedad_codigo_postal" -> "El código postal debe tener 5 dígitos"
                        "ck_foto_tamano" -> "Cada foto debe pesar entre 1 byte y 2 MB"
                        "ck_foto_tipo" -> "Solo se aceptan fotos JPEG, PNG o WebP"
                        else -> "La base de datos rechazó los datos ($restriccion)"
                    }
                )
            )
            "23503" -> ValidacionException(listOf("Falta un dato relacionado (usuario, modalidad o colonia)"))
            else -> e
        }
    }

    private companion object {
        const val SELECT_PUBLICACION = """
            SELECT p.pke_id_publicacion, p.fke_id_usuario, p.t_nombre_publicacion, p.t_descripcion_publicacion,
                   p.t_reglas_publicacion, p.e_precio_publicacion, p.fh_fecha_publicacion,
                   p.t_estado_publicacion, p.b_estatus_publicacion, p.t_motivo_rechazo_publicacion,
                   p.fh_fecha_revision_publicacion,
                   m.t_nombre_modalidad,
                   pr.t_tipo_propiedad, pr.t_calle_propiedad, pr.t_numero_propiedad,
                   pr.n_latitud_propiedad, pr.n_longitud_propiedad, pr.e_superficie_propiedad,
                   pr.t_codigo_postal_propiedad,
                   c.t_nombre_colonia,
                   i.b_amueblado_inmueble, i.e_dormitorios_inmueble, i.e_banos_inmueble,
                   i.b_mascotas_inmueble, i.e_cochera_inmueble, i.b_patio_inmueble,
                   i.e_piso_inmueble, i.b_elevador_inmueble, i.e_niveles_inmueble,
                   t.t_uso_suelo_terreno,
                   u.t_nombre_usuario AS vendedor_nombre,
                   u.t_primer_apellido_usuario AS vendedor_apellido1,
                   u.t_segundo_apellido_usuario AS vendedor_apellido2,
                   u.t_correo_usuario AS vendedor_correo,
                   u.t_telefono_usuario AS vendedor_telefono,
                   u.by_foto_usuario IS NOT NULL AS vendedor_tiene_foto,
                   (EXTRACT(EPOCH FROM u.fh_fecha_foto_usuario) * 1000)::bigint AS vendedor_foto_version,
                   r.t_nombre_usuario AS revisor_nombre,
                   r.t_primer_apellido_usuario AS revisor_apellido1,
                   COALESCE(
                       (SELECT array_agg(f.pke_id_foto ORDER BY f.e_orden_visibilidad_foto)
                        FROM foto f WHERE f.fke_id_propiedad = pr.pke_id_propiedad),
                       ARRAY[]::int[]
                   ) AS fotos
            FROM publicacion p
            JOIN modalidad m ON m.pke_id_modalidad = p.fke_id_modalidad
            JOIN propiedad pr ON pr.fke_id_publicacion = p.pke_id_publicacion
            JOIN colonia c ON c.pke_id_colonia = pr.fke_id_colonia
            LEFT JOIN inmueble i ON i.fke_id_propiedad = pr.pke_id_propiedad
            LEFT JOIN terreno t ON t.fke_id_propiedad = pr.pke_id_propiedad
            JOIN usuario u ON u.pke_id_usuario = p.fke_id_usuario
            LEFT JOIN usuario r ON r.pke_id_usuario = p.fke_id_usuario_revision
        """
    }
}
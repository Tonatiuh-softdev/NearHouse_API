package com.equipo4.nearhouse.api.publicaciones

import com.equipo4.nearhouse.api.common.ValidacionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.postgresql.util.PSQLException
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Connection
import java.sql.SQLException
import java.sql.Types
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
}
package com.equipo4.nearhouse.api.publicaciones

import com.equipo4.nearhouse.api.common.NoEncontradoException
import com.equipo4.nearhouse.api.common.ValidacionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

class PublicacionRepository(private val dataSource: DataSource) {
    suspend fun colonias(): List<ColoniaResponse> = withContext(Dispatchers.IO) {
        dataSource.connection.use { c -> c.prepareStatement("SELECT pke_id_colonia, t_nombre_colonia FROM colonia WHERE b_estatus_colonia = TRUE ORDER BY t_nombre_colonia ASC").use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(ColoniaResponse(rs.getInt(1), rs.getString(2))) } }
        } }
    }

    suspend fun crear(usuarioId: Int, req: PublicacionRequest, fotos: List<FotoEntrada>): PublicacionCreadaResponse = withContext(Dispatchers.IO) {
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                validar(c, req, fotos)
                val idModalidad = c.prepareStatement("SELECT pke_id_modalidad FROM modalidad WHERE lower(t_nombre_modalidad)=lower(?) AND b_estatus_modalidad=TRUE").use { ps ->
                    ps.setString(1, req.modalidad); ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else throw ValidacionException(listOf("La modalidad debe ser Venta o Renta")) }
                }
                val idPublicacion = c.prepareStatement("""INSERT INTO publicacion (fke_id_usuario,fke_id_modalidad,t_nombre_publicacion,t_descripcion_publicacion,e_precio_publicacion,t_estado_publicacion,t_reglas_publicacion) VALUES (?,?,?,?,?,'EN_REVISION',?) RETURNING pke_id_publicacion""").use { ps ->
                    ps.setInt(1, usuarioId); ps.setInt(2, idModalidad); ps.setString(3, req.titulo.trim()); ps.setString(4, req.descripcion.trim()); ps.setBigDecimal(5, java.math.BigDecimal.valueOf(req.precio)); ps.setString(6, req.reglas?.trim()?.takeIf { it.isNotEmpty() }); ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
                }
                val idPropiedad = c.prepareStatement("""INSERT INTO propiedad (fke_id_publicacion,t_tipo_propiedad,t_calle_propiedad,t_numero_propiedad,n_latitud_propiedad,n_longitud_propiedad,fke_id_colonia,e_superficie_propiedad,t_codigo_postal_propiedad) VALUES (?,?,?,?,?,?,?,?,?) RETURNING pke_id_propiedad""").use { ps ->
                    ps.setInt(1,idPublicacion); ps.setString(2,req.tipo); ps.setString(3,req.calle.trim()); ps.setString(4,req.numero.trim()); ps.setBigDecimal(5,java.math.BigDecimal.valueOf(req.latitud)); ps.setBigDecimal(6,java.math.BigDecimal.valueOf(req.longitud)); ps.setInt(7,req.idColonia); ps.setInt(8,req.superficie); ps.setString(9,req.codigoPostal?.takeIf { it.isNotBlank() }); ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
                }
                when (req.tipo) {
                    "DEPARTAMENTO", "CASA" -> c.prepareStatement("""INSERT INTO inmueble (fke_id_propiedad,b_amueblado_inmueble,e_dormitorios_inmueble,e_banos_inmueble,b_mascotas_inmueble,e_cochera_inmueble,b_patio_inmueble,e_piso_inmueble,b_elevador_inmueble,e_niveles_inmueble) VALUES (?,?,?,?,?,?,?,?,?,?)""").use { ps ->
                        ps.setInt(1,idPropiedad); ps.setBoolean(2,req.amueblado); ps.setInt(3,req.dormitorios); ps.setInt(4,req.banos); ps.setBoolean(5,req.mascotas); ps.setInt(6,req.cochera); ps.setBoolean(7,req.patio)
                        if (req.tipo == "DEPARTAMENTO") { ps.setObject(8,req.piso); ps.setBoolean(9,req.elevador); ps.setNull(10,java.sql.Types.INTEGER) } else { ps.setNull(8,java.sql.Types.INTEGER); ps.setBoolean(9,false); ps.setObject(10,req.niveles) }; ps.executeUpdate()
                    }
                    "TERRENO" -> c.prepareStatement("INSERT INTO terreno (fke_id_propiedad,t_uso_suelo_terreno) VALUES (?,?)").use { ps -> ps.setInt(1,idPropiedad); ps.setString(2,req.usoSuelo); ps.executeUpdate() }
                }
                val fotoIds = fotos.mapIndexed { i, foto -> c.prepareStatement("INSERT INTO foto (fke_id_propiedad,e_orden_visibilidad_foto,by_imagen_foto,t_tipo_imagen_foto) VALUES (?,?,?,?) RETURNING pke_id_foto").use { ps -> ps.setInt(1,idPropiedad); ps.setInt(2,i+1); ps.setBytes(3,foto.bytes); ps.setString(4,foto.contentType); ps.executeQuery().use { rs -> rs.next(); FotoCreadaResponse(rs.getInt(1),i+1) } } }
                c.commit(); PublicacionCreadaResponse(idPublicacion,req.titulo.trim(),"EN_REVISION",fotoIds)
            } catch (e: Exception) { try { c.rollback() } catch (_: Exception) {}; throw e } finally { try { c.autoCommit = true } catch (_: Exception) {} }
        }
    }

    private fun validar(c: Connection, r: PublicacionRequest, fotos: List<FotoEntrada>) {
        val errors = mutableListOf<String>()
        if (r.titulo.isBlank() || r.titulo.trim().length > 60) errors += "El título es obligatorio y debe tener máximo 60 caracteres"
        if (r.descripcion.trim().length !in 30..1000) errors += "La descripción debe tener entre 30 y 1000 caracteres"
        if (r.precio <= 0) errors += "El precio debe ser mayor que cero"
        if (r.modalidad !in listOf("Venta", "Renta")) errors += "La modalidad debe ser Venta o Renta"
        if (r.tipo !in listOf("DEPARTAMENTO", "CASA", "TERRENO")) errors += "El tipo debe ser DEPARTAMENTO, CASA o TERRENO"
        if (r.calle.isBlank() || r.numero.isBlank()) errors += "La calle y el número son obligatorios"
        if (!r.latitud.isFinite() || r.latitud !in -90.0..90.0) errors += "La latitud debe estar entre -90 y 90"
        if (!r.longitud.isFinite() || r.longitud !in -180.0..180.0) errors += "La longitud debe estar entre -180 y 180"
        if (r.superficie <= 0) errors += "La superficie debe ser mayor que cero"
        if (r.codigoPostal != null && !r.codigoPostal.matches(Regex("^[0-9]{5}$"))) errors += "El código postal debe contener 5 dígitos"
        if (r.dormitorios < 0 || r.banos < 0 || r.cochera < 0) errors += "Dormitorios, baños y cochera no pueden ser negativos"
        if (r.reglas != null && r.reglas.length > 300) errors += "Las reglas no pueden superar 300 caracteres"
        when(r.tipo) {
            "DEPARTAMENTO" -> { if (r.piso == null || r.piso < 0) errors += "El departamento requiere piso (0 = planta baja)" }
            "CASA" -> { if (r.niveles == null || r.niveles < 1) errors += "La casa requiere al menos un nivel" }
            "TERRENO" -> { if (r.usoSuelo !in listOf("HABITACIONAL", "AGRICOLA")) errors += "El uso del suelo debe ser HABITACIONAL o AGRICOLA" }
        }
        if (fotos.size !in 1..5) errors += "Debes enviar de 1 a 5 fotos"
        fotos.forEachIndexed { i,f -> if (f.contentType !in listOf("image/jpeg","image/png","image/webp")) errors += "La foto ${i+1} debe ser JPEG, PNG o WebP"; if (f.bytes.isEmpty() || f.bytes.size > 2*1024*1024) errors += "La foto ${i+1} debe pesar como máximo 2 MB" }
        if (errors.isNotEmpty()) throw ValidacionException(errors)
        val coloniaActiva = c.prepareStatement("SELECT 1 FROM colonia WHERE pke_id_colonia=? AND b_estatus_colonia=TRUE").use { ps -> ps.setInt(1,r.idColonia); ps.executeQuery().use { it.next() } }
        if (!coloniaActiva) throw ValidacionException(listOf("La colonia no existe o está inactiva"))
    }

    suspend fun mias(usuarioId: Int): List<MiPublicacionResponse> = withContext(Dispatchers.IO) {
        dataSource.connection.use { c -> c.prepareStatement("""SELECT pu.pke_id_publicacion,pu.t_nombre_publicacion,pu.t_descripcion_publicacion,pu.e_precio_publicacion,mo.t_nombre_modalidad,pr.t_tipo_propiedad,pr.t_calle_propiedad,pr.t_numero_propiedad,pr.n_latitud_propiedad,pr.n_longitud_propiedad,pr.fke_id_colonia,co.t_nombre_colonia,pr.e_superficie_propiedad,pu.t_estado_publicacion,pu.fh_fecha_publicacion,im.e_dormitorios_inmueble,im.e_banos_inmueble,im.e_cochera_inmueble,im.e_piso_inmueble,im.b_elevador_inmueble,im.e_niveles_inmueble,te.t_uso_suelo_terreno FROM publicacion pu JOIN propiedad pr ON pr.fke_id_publicacion=pu.pke_id_publicacion JOIN colonia co ON co.pke_id_colonia=pr.fke_id_colonia JOIN modalidad mo ON mo.pke_id_modalidad=pu.fke_id_modalidad LEFT JOIN inmueble im ON im.fke_id_propiedad=pr.pke_id_propiedad LEFT JOIN terreno te ON te.fke_id_propiedad=pr.pke_id_propiedad WHERE pu.fke_id_usuario=? ORDER BY pu.fh_fecha_publicacion DESC""").use { ps ->
            ps.setInt(1,usuarioId); ps.executeQuery().use { rs -> buildList { while(rs.next()) { val id=rs.getInt("pke_id_publicacion"); val photos=c.prepareStatement("SELECT f.pke_id_foto FROM foto f JOIN propiedad p ON p.pke_id_propiedad=f.fke_id_propiedad WHERE p.fke_id_publicacion=? ORDER BY f.e_orden_visibilidad_foto").use { fp -> fp.setInt(1,id); fp.executeQuery().use { fr -> buildList { while(fr.next()) add(fr.getInt(1)) } } }; add(MiPublicacionResponse(id,rs.getString("t_nombre_publicacion"),rs.getString("t_descripcion_publicacion"),rs.getBigDecimal("e_precio_publicacion").toDouble(),rs.getString("t_nombre_modalidad"),rs.getString("t_tipo_propiedad"),rs.getString("t_calle_propiedad"),rs.getString("t_numero_propiedad"),rs.getBigDecimal("n_latitud_propiedad").toDouble(),rs.getBigDecimal("n_longitud_propiedad").toDouble(),rs.getInt("fke_id_colonia"),rs.getString("t_nombre_colonia"),rs.getInt("e_superficie_propiedad"),rs.getString("t_estado_publicacion"),rs.getObject("fh_fecha_publicacion",java.time.OffsetDateTime::class.java).toString(),photos,rs.getObject("e_dormitorios_inmueble") as? Int,rs.getObject("e_banos_inmueble") as? Int,rs.getObject("e_cochera_inmueble") as? Int,rs.getObject("e_piso_inmueble") as? Int,rs.getObject("b_elevador_inmueble") as? Boolean,rs.getObject("e_niveles_inmueble") as? Int,rs.getString("t_uso_suelo_terreno"))) } } }
        } }
    }
    suspend fun foto(id: Int): Pair<String, ByteArray>? = withContext(Dispatchers.IO) { dataSource.connection.use { c -> c.prepareStatement("SELECT t_tipo_imagen_foto,by_imagen_foto FROM foto WHERE pke_id_foto=?").use { ps -> ps.setInt(1,id); ps.executeQuery().use { rs -> if(rs.next()) rs.getString(1) to rs.getBytes(2) else null } } } }
}

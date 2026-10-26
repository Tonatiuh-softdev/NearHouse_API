package com.equipo4.nearhouse.api.colonias

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sql.DataSource

/**
 * Única capa que habla con PostgreSQL para el catálogo de colonias.
 * Lee de la vista vw_colonia_anuncios (migración 03), que solo incluye colonias
 * activas con anuncios en estado PUBLICADA y activos.
 */
class ColoniaRepository(private val dataSource: DataSource) {

    /**
     * Colonias que tienen al menos un anuncio publicado.
     * [modalidad] ("Venta"/"Renta") y [tipo] ("CASA", "DEPARTAMENTO", "TERRENO") son opcionales:
     * si vienen nulos, no filtran.
     */
    suspend fun listarConAnuncios(modalidad: String?, tipo: String?): List<ColoniaResponse> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(SELECT_COLONIAS).use { ps ->
                    ps.setString(1, modalidad)
                    ps.setString(2, modalidad)
                    ps.setString(3, tipo)
                    ps.setString(4, tipo)
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    ColoniaResponse(
                                        id = rs.getInt("id_colonia"),
                                        nombre = rs.getString("nombre_colonia"),
                                        totalAnuncios = rs.getInt("total")
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

    private companion object {
        const val SELECT_COLONIAS = """
            SELECT id_colonia, nombre_colonia, SUM(total_anuncios)::int AS total
            FROM vw_colonia_anuncios
            WHERE (?::text IS NULL OR LOWER(modalidad) = LOWER(?::text))
              AND (?::text IS NULL OR tipo = ?::text)
            GROUP BY id_colonia, nombre_colonia
        """
    }
}
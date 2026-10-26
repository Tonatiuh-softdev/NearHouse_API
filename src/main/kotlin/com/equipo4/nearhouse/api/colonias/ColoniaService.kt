package com.equipo4.nearhouse.api.colonias

import com.equipo4.nearhouse.api.common.ValidacionException
import java.text.Collator
import java.util.Locale

/**
 * Reglas del catálogo de colonias: validar los filtros y ordenar alfabéticamente
 * (en español, sin que los acentos descompongan el orden).
 */
class ColoniaService(private val repositorio: ColoniaRepository) {

    suspend fun listarConAnuncios(modalidad: String?, tipo: String?): List<ColoniaResponse> {
        val modalidadLimpia = modalidad?.trim()?.takeIf { it.isNotEmpty() }
        val tipoLimpio = tipo?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()

        val errores = mutableListOf<String>()
        if (modalidadLimpia != null && modalidadLimpia.lowercase() !in MODALIDADES) {
            errores += "La modalidad debe ser Venta o Renta"
        }
        if (tipoLimpio != null && tipoLimpio !in TIPOS) {
            errores += "El tipo debe ser DEPARTAMENTO, CASA o TERRENO"
        }
        if (errores.isNotEmpty()) throw ValidacionException(errores)

        return repositorio.listarConAnuncios(modalidadLimpia, tipoLimpio)
            .sortedWith { a, b -> COLLATOR.compare(a.nombre, b.nombre) }
    }

    private companion object {
        val MODALIDADES = setOf("venta", "renta")
        val TIPOS = setOf("DEPARTAMENTO", "CASA", "TERRENO")
        val COLLATOR: Collator = Collator.getInstance(Locale.forLanguageTag("es-MX")).apply {
            strength = Collator.PRIMARY
        }
    }
}
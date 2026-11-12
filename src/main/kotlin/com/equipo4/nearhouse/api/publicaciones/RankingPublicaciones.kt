package com.equipo4.nearhouse.api.publicaciones

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ln
import kotlin.math.pow

/**
 * Orden de los anuncios en Inicio y en el Mapa según el plan de cada vendedor.
 *
 * 1. PESO de cada anuncio:
 *    - Plan del vendedor: Básico (visibilidad 1) = 1, Profesional (2) = 2, Premium (3) = 4.
 *      Los anuncios de administradores pesan 1.
 *    - Nuevo: x1.5 durante sus primeros 7 días desde que se aprobó.
 *    - Variedad: dentro de un mismo vendedor, su 2.º anuncio pesa x0.7, el 3.º x0.49, etc.,
 *      para que un solo vendedor no llene la pantalla.
 *
 * 2. SORTEO PONDERADO (Efraimidis-Spirakis): cada anuncio recibe un número al azar u entre 0 y 1
 *    y se ordena por ln(u) / peso, de mayor a menor. La probabilidad de quedar en primer lugar es
 *    peso / (suma de los pesos): un Premium tiene 4 veces más probabilidad que un Básico, pero un
 *    Básico también puede salir arriba.
 *
 * 3. AZAR ESTABLE: el número depende del día (hora de Colima), del usuario y del anuncio. La misma
 *    persona ve el mismo orden todo el día (no brinca al recargar), y cambia cada día y entre usuarios.
 *
 * Además, [aplicarLimites] oculta los anuncios que exceden el límite del plan del vendedor
 * (por ejemplo, si bajó de Profesional a Básico): se quedan visibles los más recientes.
 */
object RankingPublicaciones {

    val PESO_POR_NIVEL = mapOf(1 to 1.0, 2 to 2.0, 3 to 4.0)
    const val BONO_NUEVO = 1.5
    const val DIAS_NUEVO = 7L
    const val FACTOR_VARIEDAD = 0.7

    private const val MS_POR_DIA = 86_400_000L
    private val ZONA = ZoneId.of("America/Mexico_City")

    /** Quita los anuncios que exceden el límite del plan de su vendedor. */
    fun aplicarLimites(anuncios: List<PublicacionResponse>): List<PublicacionResponse> {
        val ocultos = idsFueraDeLimite(anuncios)
        return if (ocultos.isEmpty()) anuncios else anuncios.filter { it.id !in ocultos }
    }

    /**
     * Anuncios publicados y activos que exceden el límite del plan de su vendedor.
     * De cada vendedor se quedan visibles los más recientes; los demás esperan su turno.
     */
    fun idsFueraDeLimite(anuncios: List<PublicacionResponse>): Set<Int> =
        anuncios
            .filter { it.estado == "PUBLICADA" && it.activa && !it.desactivadaPorAdmin }
            .groupBy { it.vendedor.id }
            .flatMap { (_, delVendedor) ->
                val limite = delVendedor.first().limiteVendedor ?: return@flatMap emptyList<Int>()
                delVendedor.sortedByDescending { fechaVisible(it) }.drop(limite).map { it.id }
            }
            .toSet()

    /** Ordena los anuncios con el sorteo ponderado. [usuarioId] null = sin sesión. */
    fun ordenar(
        anuncios: List<PublicacionResponse>,
        usuarioId: Int?,
        ahoraMs: Long = System.currentTimeMillis(),
        dia: LocalDate = LocalDate.now(ZONA),
    ): List<PublicacionResponse> {
        if (anuncios.size < 2) return anuncios
        val semilla = dia.toEpochDay() * 1_000_003L + (usuarioId ?: 0)
        val azar = anuncios.associate { it.id to aleatorio(semilla, it.id) }
        val pesoBase = anuncios.associate { it.id to pesoBase(it, ahoraMs) }

        // Variedad: el mejor anuncio de cada vendedor pesa completo, el siguiente x0.7, luego x0.49...
        val pesoFinal = HashMap<Int, Double>()
        anuncios.groupBy { it.vendedor.id }.values.forEach { delVendedor ->
            delVendedor
                .sortedByDescending { clave(azar.getValue(it.id), pesoBase.getValue(it.id)) }
                .forEachIndexed { k, anuncio ->
                    pesoFinal[anuncio.id] = pesoBase.getValue(anuncio.id) * FACTOR_VARIEDAD.pow(k)
                }
        }
        return anuncios.sortedByDescending { clave(azar.getValue(it.id), pesoFinal.getValue(it.id)) }
    }

    /** Peso por plan y por ser nuevo (sin contar la variedad). */
    fun pesoBase(anuncio: PublicacionResponse, ahoraMs: Long): Double {
        val plan = PESO_POR_NIVEL[anuncio.nivelVisibilidad] ?: 1.0
        val edadDias = ((ahoraMs - fechaVisible(anuncio)) / MS_POR_DIA).coerceAtLeast(0)
        val nuevo = if (edadDias < DIAS_NUEVO) BONO_NUEVO else 1.0
        return plan * nuevo
    }

    /** Desde cuándo se ve: la fecha en que se aprobó (o en que se creó, si no hay revisión). */
    private fun fechaVisible(anuncio: PublicacionResponse): Long = anuncio.revision?.fecha ?: anuncio.fechaRegistro

    /** Equivale a u^(1/peso): mayor = más arriba. */
    private fun clave(u: Double, peso: Double): Double = ln(u) / peso

    /** Número "al azar" entre 0 y 1 (sin incluirlos), siempre el mismo para la misma semilla y anuncio. */
    private fun aleatorio(semilla: Long, id: Int): Double {
        val x = mezclar(semilla * 31 + id)
        return ((x ushr 11).toDouble() + 0.5) / (1L shl 53).toDouble()
    }

    /** SplitMix64: revuelve los bits para que semillas parecidas den números muy distintos. */
    private fun mezclar(valor: Long): Long {
        var z = valor + 0x9E3779B97F4A7C15uL.toLong()
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
        return z xor (z ushr 31)
    }
}

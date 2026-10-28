package com.equipo4.nearhouse.api.auth

import kotlin.math.ceil

/**
 * Límite de intentos de login: después de 5 intentos fallidos con el mismo correo
 * dentro de 15 minutos, se deja de revisar contraseñas hasta que el más antiguo expire.
 *
 * Vive en memoria: si el servidor se reinicia, los contadores empiezan de cero.
 */
object LoginAttemptTracker {
    private const val MAX_INTENTOS = 5
    private const val VENTANA_MS = 15 * 60 * 1000L
    private const val MAX_CORREOS_EN_MEMORIA = 10_000

    private val lock = Any()
    private val fallos = HashMap<String, ArrayDeque<Long>>()

    /** Minutos que faltan para poder reintentar, o null si el correo puede intentar ahora. */
    fun minutosRestantes(correo: String, ahora: Long = System.currentTimeMillis()): Int? =
        synchronized(lock) {
            val lista = fallos[correo] ?: return null
            limpiarVencidos(lista, ahora)
            if (lista.isEmpty()) {
                fallos.remove(correo)
                return null
            }
            if (lista.size < MAX_INTENTOS) return null

            val faltanMs = VENTANA_MS - (ahora - lista.first())
            ceil(faltanMs / 60_000.0).toInt().coerceAtLeast(1)
        }

    /** Registra un intento fallido. Se llama también cuando el correo no existe. */
    fun registrarFallo(correo: String, ahora: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            if (fallos.size >= MAX_CORREOS_EN_MEMORIA) purgarTodo(ahora)
            val lista = fallos.getOrPut(correo) { ArrayDeque() }
            limpiarVencidos(lista, ahora)
            lista.addLast(ahora)
        }
    }

    /** Borra el contador cuando el usuario entra con la contraseña correcta. */
    fun limpiar(correo: String) {
        synchronized(lock) { fallos.remove(correo) }
    }

    private fun limpiarVencidos(lista: ArrayDeque<Long>, ahora: Long) {
        while (lista.isNotEmpty() && ahora - lista.first() > VENTANA_MS) lista.removeFirst()
    }

    private fun purgarTodo(ahora: Long) {
        val iterador = fallos.entries.iterator()
        while (iterador.hasNext()) {
            val lista = iterador.next().value
            limpiarVencidos(lista, ahora)
            if (lista.isEmpty()) iterador.remove()
        }
    }
}
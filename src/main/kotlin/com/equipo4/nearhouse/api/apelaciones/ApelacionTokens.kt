package com.equipo4.nearhouse.api.apelaciones

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.util.Date

/**
 * Comprobante para apelar una suspensión sin sesión. Lo entrega el inicio de sesión
 * cuando la contraseña (o Google) ya se verificó y la cuenta resulta suspendida.
 * Guarda la cuenta y la fecha de la suspensión: si después hay otra suspensión, ya no sirve.
 */
class ApelacionTokens(jwtSecret: String) {
    private val algorithm = Algorithm.HMAC256(jwtSecret)

    data class Datos(val usuarioId: Int, val fechaMedidaMs: Long)

    fun crear(usuarioId: Int, fechaMedidaMs: Long): String =
        JWT.create()
            .withAudience(AUDIENCIA)
            .withClaim("uid", usuarioId)
            .withClaim("medida", fechaMedidaMs)
            .withExpiresAt(Date(System.currentTimeMillis() + DURACION_MS))
            .sign(algorithm)

    /** Null si el comprobante no es válido o ya venció. */
    fun verificar(token: String): Datos? = try {
        val jwt = JWT.require(algorithm).withAudience(AUDIENCIA).build().verify(token)
        val id = jwt.getClaim("uid").asInt()
        val medida = jwt.getClaim("medida").asLong()
        if (id == null || medida == null) null else Datos(id, medida)
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val AUDIENCIA = "apelacion_suspension"
        const val DURACION_MS = 30 * 60 * 1000L
    }
}

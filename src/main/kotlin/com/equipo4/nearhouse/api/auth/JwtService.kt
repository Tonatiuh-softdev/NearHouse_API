package com.equipo4.nearhouse.api.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.JWTVerifier
import com.equipo4.nearhouse.api.config.AppConfig
import java.util.Date

/**
 * Crea y verifica los tokens JWT. El token es la "credencial" que la app guarda
 * después del login y manda en cada petición protegida:
 *   Authorization: Bearer <token>
 */
class JwtService(private val config: AppConfig) {

    private val algorithm = Algorithm.HMAC256(config.jwtSecret)

    val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(config.jwtIssuer)
        .withAudience(config.jwtAudience)
        .build()

    fun generarToken(usuarioId: Int, rol: String): String {
        val ahora = System.currentTimeMillis()
        return JWT.create()
            .withIssuer(config.jwtIssuer)
            .withAudience(config.jwtAudience)
            .withClaim(CLAIM_ID, usuarioId)
            .withClaim(CLAIM_ROL, rol)
            .withIssuedAt(Date(ahora))
            .withExpiresAt(Date(ahora + config.jwtExpiracionHoras * 3_600_000))
            .sign(algorithm)
    }

    companion object {
        const val AUTH_NAME = "auth-jwt"
        const val CLAIM_ID = "id"
        const val CLAIM_ROL = "rol"
    }
}

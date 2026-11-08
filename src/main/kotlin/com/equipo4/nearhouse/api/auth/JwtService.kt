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

    fun generarToken(usuarioId: Int, rol: String, versionSesion: Int = 1): String {
        val ahora = System.currentTimeMillis()
        return JWT.create()
            .withIssuer(config.jwtIssuer)
            .withAudience(config.jwtAudience)
            .withClaim(CLAIM_ID, usuarioId)
            .withClaim(CLAIM_ROL, rol)
            .withClaim(CLAIM_VERSION, versionSesion)
            .withIssuedAt(Date(ahora))
            .withExpiresAt(Date(ahora + config.jwtExpiracionHoras * 3_600_000))
            .sign(algorithm)
    }

    /** Genera un comprobante JWT para cambio/creación de contraseña (10 min). */
    fun generarComprobanteContrasena(usuarioId: Int, versionSesion: Int): String {
        val ahora = System.currentTimeMillis()
        return JWT.create()
            .withIssuer(config.jwtIssuer)
            .withAudience(AUDIENCE_PROOF_CONTRASENA)
            .withClaim(CLAIM_ID, usuarioId)
            .withClaim(CLAIM_VERSION, versionSesion)
            .withClaim(CLAIM_PROPOSITO, PROPOSITO_CONTRASENA)
            .withIssuedAt(Date(ahora))
            .withExpiresAt(Date(ahora + 10 * 60 * 1000)) // 10 minutos
            .sign(algorithm)
    }

    /** Valida el comprobante y regresa Pair(usuarioId, versionSesion) o null si es inválido/expirado. */
    fun obtenerComprobanteContrasena(token: String?): Pair<Int, Int>? {
        if (token.isNullOrBlank()) return null
        return try {
            val verifierProof = JWT.require(algorithm)
                .withIssuer(config.jwtIssuer)
                .withAudience(AUDIENCE_PROOF_CONTRASENA)
                .withClaim(CLAIM_PROPOSITO, PROPOSITO_CONTRASENA)
                .build()
            val decoded = verifierProof.verify(token)
            val id = decoded.getClaim(CLAIM_ID).asInt()
            val ver = decoded.getClaim(CLAIM_VERSION).asInt()
            if (id != null && ver != null) Pair(id, ver) else null
        } catch (e: Exception) {
            null
        }
    }

    /** Genera un comprobante JWT para recuperación de contraseña (10 min). */
    fun generarComprobanteRecuperar(usuarioId: Int, versionSesion: Int): String {
        val ahora = System.currentTimeMillis()
        return JWT.create()
            .withIssuer(config.jwtIssuer)
            .withAudience(AUDIENCE_PROOF_RECUPERAR)
            .withClaim(CLAIM_ID, usuarioId)
            .withClaim(CLAIM_VERSION, versionSesion)
            .withClaim(CLAIM_PROPOSITO, PROPOSITO_RECUPERAR)
            .withIssuedAt(Date(ahora))
            .withExpiresAt(Date(ahora + 10 * 60 * 1000)) // 10 minutos
            .sign(algorithm)
    }

    /** Valida el comprobante de recuperación y regresa Pair(usuarioId, versionSesion) o null si es inválido. */
    fun obtenerComprobanteRecuperar(token: String?): Pair<Int, Int>? {
        if (token.isNullOrBlank()) return null
        return try {
            val verifierProof = JWT.require(algorithm)
                .withIssuer(config.jwtIssuer)
                .withAudience(AUDIENCE_PROOF_RECUPERAR)
                .withClaim(CLAIM_PROPOSITO, PROPOSITO_RECUPERAR)
                .build()
            val decoded = verifierProof.verify(token)
            val id = decoded.getClaim(CLAIM_ID).asInt()
            val ver = decoded.getClaim(CLAIM_VERSION).asInt()
            if (id != null && ver != null) Pair(id, ver) else null
        } catch (e: Exception) {
            null
        }
    }

    /** Genera un comprobante JWT firmado al verificar el código de correo. */
    fun generarComprobanteCorreo(correo: String): String {
        val ahora = System.currentTimeMillis()
        return JWT.create()
            .withIssuer(config.jwtIssuer)
            .withAudience(config.jwtAudience)
            .withClaim(CLAIM_CORREO, correo.lowercase().trim())
            .withIssuedAt(Date(ahora))
            .withExpiresAt(Date(ahora + 15 * 60 * 1000)) // Expira en 15 minutos
            .sign(algorithm)
    }

    /** Decodifica el comprobante JWT y regresa el correo si la firma es válida y no ha expirado. */
    fun obtenerCorreoDeComprobante(token: String?): String? {
        if (token.isNullOrBlank()) return null
        return try {
            val decoded = verifier.verify(token)
            decoded.getClaim(CLAIM_CORREO).asString()
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val AUTH_NAME = "auth-jwt"
        const val CLAIM_ID = "id"
        const val CLAIM_ROL = "rol"
        const val CLAIM_CORREO = "correo"
        const val CLAIM_VERSION = "ver"
        const val CLAIM_PROPOSITO = "proposito"
        const val PROPOSITO_CONTRASENA = "contrasena"
        const val PROPOSITO_RECUPERAR = "recuperar"
        const val AUDIENCE_PROOF_CONTRASENA = "nearhouse-proof-contrasena"
        const val AUDIENCE_PROOF_RECUPERAR = "nearhouse-proof-recuperar"
    }
}

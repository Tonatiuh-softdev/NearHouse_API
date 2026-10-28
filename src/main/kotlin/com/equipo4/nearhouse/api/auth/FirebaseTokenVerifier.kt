package com.equipo4.nearhouse.api.auth

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import java.net.URL
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

data class FirebasePayload(
    val googleId: String,
    val email: String,
    val name: String,
    val givenName: String,
    val familyName: String
)

/**
 * Revisa los tokens que Firebase le da a la app: el del login con Google y el de la verificación por SMS.
 * Solo acepta tokens de NUESTRO proyecto de Firebase (FIREBASE_PROJECT_ID).
 */
class FirebaseTokenVerifier(private val projectId: String = "nearhouse-70367") {
    private val jwkProvider = JwkProviderBuilder(
        URL("https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com")
    )
    .cached(10, 24, TimeUnit.HOURS)
    .rateLimited(10, 1, TimeUnit.MINUTES)
    .build()

    /**
     * Revisa la firma de Google y que el token sea de nuestro proyecto. Si no pasa, lanza una excepción.
     *
     * El proyecto NO se saca del token: Google firma con las mismas llaves los tokens de TODOS
     * los proyectos de Firebase. Si se aceptara el proyecto que dice el token, alguien podría
     * crear su propio proyecto de Firebase y mandar tokens con el correo o teléfono de otra persona.
     */
    private fun verificarFirma(idToken: String): DecodedJWT {
        val jwt = JWT.decode(idToken)
        val jwk = jwkProvider.get(jwt.keyId)
        val algorithm = Algorithm.RSA256(jwk.publicKey as RSAPublicKey, null)
        return JWT.require(algorithm)
            .withIssuer("https://securetoken.google.com/$projectId")
            .withAudience(projectId)
            .acceptLeeway(60) // tolera 1 minuto de diferencia entre relojes
            .build()
            .verify(idToken)
    }

    /**
     * Revisa el token que manda la app después de confirmar el código del SMS.
     * Regresa el teléfono que Firebase verificó (por ejemplo "+523141234567"),
     * o null si el token no es válido, ya expiró o no trae teléfono.
     */
    fun verificarTelefono(idToken: String): String? {
        return try {
            val token = verificarFirma(idToken.trim())
            // phone_number solo viene en el token si Firebase verificó ese número por SMS
            token.getClaim("phone_number").asString()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            println("Token de SMS rechazado: ${e.message}")
            null
        }
    }

    fun verifyToken(idToken: String): FirebasePayload? {
        return try {
            val verifiedJwt = verificarFirma(idToken)

            val firebaseClaim = verifiedJwt.getClaim("firebase")?.asMap()
            val signInProvider = firebaseClaim?.get("sign_in_provider") as? String
            val emailVerified = verifiedJwt.getClaim("email_verified")?.asBoolean()
                ?: (verifiedJwt.getClaim("email")?.asString()?.isNotBlank() == true)

            if (signInProvider != "google.com" || !emailVerified) {
                println("FirebaseTokenVerifier: signInProvider=$signInProvider, emailVerified=$emailVerified")
                return null
            }

            // Extraer el ID único de Google (si está en identities) o el UID de Firebase
            val identities = firebaseClaim?.get("identities") as? Map<*, *>
            val googleIdentities = identities?.get("google.com") as? List<*>
            val googleId = googleIdentities?.firstOrNull()?.toString() ?: verifiedJwt.subject

            val email = verifiedJwt.getClaim("email")?.asString().orEmpty()
            val fullName = verifiedJwt.getClaim("name")?.asString().orEmpty()
            
            val givenName = verifiedJwt.getClaim("given_name")?.asString()
                ?.takeIf { it.isNotBlank() }
                ?: fullName.split(" ").firstOrNull().orEmpty()

            val familyName = verifiedJwt.getClaim("family_name")?.asString()
                ?.takeIf { it.isNotBlank() }
                ?: fullName.split(" ").drop(1).joinToString(" ")

            FirebasePayload(
                googleId = googleId,
                email = email,
                name = fullName,
                givenName = givenName,
                familyName = familyName
            )
        } catch (e: Exception) {
            println("Error verificando Firebase Token: ${e.message}")
            null
        }
    }
}

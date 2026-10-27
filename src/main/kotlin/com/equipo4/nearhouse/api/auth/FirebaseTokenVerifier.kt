package com.equipo4.nearhouse.api.auth

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
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

class FirebaseTokenVerifier(private val projectId: String = "nearhouse-70367") {
    private val jwkProvider = JwkProviderBuilder(
        URL("https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com")
    )
    .cached(10, 24, TimeUnit.HOURS)
    .rateLimited(10, 1, TimeUnit.MINUTES)
    .build()

    fun verifyToken(idToken: String): FirebasePayload? {
        return try {
            val jwt = JWT.decode(idToken)
            val issuer = jwt.issuer.orEmpty()

            // Extraer el project ID del issuer del token si coincide con Firebase ("https://securetoken.google.com/<projectId>")
            val tokenProjectId = if (issuer.startsWith("https://securetoken.google.com/")) {
                issuer.removePrefix("https://securetoken.google.com/").ifBlank { projectId }
            } else {
                projectId
            }

            val jwk = jwkProvider.get(jwt.keyId)
            val algorithm = Algorithm.RSA256(jwk.publicKey as RSAPublicKey, null)

            val verifier = JWT.require(algorithm)
                .withIssuer("https://securetoken.google.com/$tokenProjectId")
                .withAudience(tokenProjectId)
                .build()

            val verifiedJwt = verifier.verify(idToken)

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

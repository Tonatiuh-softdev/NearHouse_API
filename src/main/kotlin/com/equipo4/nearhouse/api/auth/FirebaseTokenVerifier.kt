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

class FirebaseTokenVerifier(private val projectId: String) {
    private val jwkProvider = JwkProviderBuilder(
        URL("https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com")
    )
    .cached(10, 24, TimeUnit.HOURS)
    .rateLimited(10, 1, TimeUnit.MINUTES)
    .build()

    fun verifyToken(idToken: String): FirebasePayload? {
        return try {
            val jwt = JWT.decode(idToken)
            val jwk = jwkProvider.get(jwt.keyId)
            val algorithm = Algorithm.RSA256(jwk.publicKey as RSAPublicKey, null)

            val verifier = JWT.require(algorithm)
                .withIssuer("https://securetoken.google.com/$projectId")
                .withAudience(projectId)
                .build()

            val verifiedJwt = verifier.verify(idToken)

            val firebaseClaim = verifiedJwt.getClaim("firebase").asMap()
            val signInProvider = firebaseClaim?.get("sign_in_provider") as? String
            val emailVerified = verifiedJwt.getClaim("email_verified").asBoolean() ?: false

            if (signInProvider != "google.com" || !emailVerified) {
                println("FirebaseTokenVerifier: signInProvider=$signInProvider, emailVerified=$emailVerified")
                return null
            }

            FirebasePayload(
                googleId = verifiedJwt.subject,
                email = verifiedJwt.getClaim("email").asString(),
                name = verifiedJwt.getClaim("name")?.asString() ?: "",
                givenName = verifiedJwt.getClaim("given_name")?.asString() ?: "",
                familyName = verifiedJwt.getClaim("family_name")?.asString() ?: ""
            )
        } catch (e: Exception) {
            println("Error verificando Firebase Token con projectId '$projectId': ${e.message}")
            null
        }
    }
}

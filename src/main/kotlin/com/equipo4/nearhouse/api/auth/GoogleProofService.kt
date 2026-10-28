package com.equipo4.nearhouse.api.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.util.Date

data class GoogleProofData(
    val googleId: String,
    val email: String,
    val givenName: String,
    val familyName: String
)

class GoogleProofService(jwtSecret: String) {
    private val algorithm = Algorithm.HMAC256(jwtSecret)
    private val proofAudience = "google_registration_proof"

    fun createProofToken(payload: FirebasePayload): String {
        return JWT.create()
            .withAudience(proofAudience)
            .withClaim("google_id", payload.googleId)
            .withClaim("email", payload.email)
            .withClaim("given_name", payload.givenName)
            .withClaim("family_name", payload.familyName)
            .withExpiresAt(Date(System.currentTimeMillis() + 30 * 60 * 1000)) // 30 mins
            .sign(algorithm)
    }

    fun verifyProofToken(token: String): GoogleProofData? {
        return try {
            val verifier = JWT.require(algorithm)
                .withAudience(proofAudience)
                .build()
            val jwt = verifier.verify(token)
            GoogleProofData(
                googleId = jwt.getClaim("google_id").asString(),
                email = jwt.getClaim("email").asString(),
                givenName = jwt.getClaim("given_name")?.asString() ?: "",
                familyName = jwt.getClaim("family_name")?.asString() ?: ""
            )
        } catch (e: Exception) {
            null
        }
    }
}
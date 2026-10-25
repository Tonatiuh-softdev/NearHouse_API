package com.equipo4.nearhouse.api.plugins

import com.equipo4.nearhouse.api.auth.JwtService
import com.equipo4.nearhouse.api.common.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.respond

/**
 * Configura la validación de tokens. Las rutas dentro de
 * authenticate(JwtService.AUTH_NAME) { ... } exigen un token válido.
 */
fun Application.configureSecurity(jwtService: JwtService) {
    install(Authentication) {
        jwt(JwtService.AUTH_NAME) {
            realm = "NearHouse API"
            verifier(jwtService.verifier)
            validate { credential ->
                val id = credential.payload.getClaim(JwtService.CLAIM_ID).asInt()
                if (id != null) JWTPrincipal(credential.payload) else null
            }
            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse("Necesitas iniciar sesión: el token falta, no es válido o ya expiró"),
                )
            }
        }
    }
}

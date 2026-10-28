package com.equipo4.nearhouse.api.common

import com.equipo4.nearhouse.api.auth.JwtService
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import kotlin.coroutines.cancellation.CancellationException

/**
 * Lee el JSON de la petición. Si viene mal formado o le faltan campos,
 * responde 400 con un mensaje claro en lugar de un error genérico.
 */
suspend inline fun <reified T : Any> ApplicationCall.recibir(): T =
    try {
        receive<T>()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw CuerpoInvalidoException()
    }

/** Id del usuario que viene dentro del token JWT de la petición. */
fun ApplicationCall.usuarioId(): Int =
    principal<JWTPrincipal>()?.payload?.getClaim(JwtService.CLAIM_ID)?.asInt()
        ?: throw NoAutorizadoException("Token inválido")

/** Rol del usuario que viene dentro del token JWT de la petición. */
fun ApplicationCall.usuarioRol(): String =
    principal<JWTPrincipal>()?.payload?.getClaim(JwtService.CLAIM_ROL)?.asString()
        ?: throw NoAutorizadoException("Token inválido")

package com.equipo4.nearhouse.api.common

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/** Formato único de error que recibe la app: {"error": "...", "detalles": ["..."]} */
@Serializable
data class ErrorResponse(
    val error: String,
    val detalles: List<String> = emptyList(),
)

/**
 * Excepciones de la API. Cada una ya sabe qué código HTTP le corresponde;
 * el plugin StatusPages las convierte en la respuesta JSON.
 */
open class ApiException(
    val status: HttpStatusCode,
    override val message: String,
    val detalles: List<String> = emptyList(),
) : RuntimeException(message)

class ValidacionException(detalles: List<String>) :
    ApiException(HttpStatusCode.BadRequest, "Los datos enviados no son válidos", detalles)

class CuerpoInvalidoException :
    ApiException(
        HttpStatusCode.BadRequest,
        "El cuerpo de la petición no es válido. Debe ser JSON y traer todos los campos obligatorios.",
    )

class NoAutorizadoException(message: String) : ApiException(HttpStatusCode.Unauthorized, message)

class ProhibidoException(message: String) : ApiException(HttpStatusCode.Forbidden, message)

class NoEncontradoException(message: String) : ApiException(HttpStatusCode.NotFound, message)

class ConflictoException(message: String) : ApiException(HttpStatusCode.Conflict, message)

class DemasiadosIntentosException(message: String) :
    ApiException(HttpStatusCode.TooManyRequests, message)

class ServicioNoDisponibleException(mensaje: String) :
    ApiException(HttpStatusCode.ServiceUnavailable, mensaje)

class DemasiadasSolicitudesException(mensaje: String) :
    ApiException(HttpStatusCode.TooManyRequests, mensaje)

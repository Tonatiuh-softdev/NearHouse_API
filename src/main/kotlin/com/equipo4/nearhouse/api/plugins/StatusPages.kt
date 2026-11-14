package com.equipo4.nearhouse.api.plugins

import com.equipo4.nearhouse.api.apelaciones.CuentaSuspendidaException
import com.equipo4.nearhouse.api.common.ApiException
import com.equipo4.nearhouse.api.common.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond

/**
 * Convierte cualquier error en una respuesta JSON con el código HTTP correcto.
 * Así ningún error "truena" la API ni le muestra detalles internos a la app.
 */
fun Application.configureStatusPages() {
    install(StatusPages) {
        // Cuenta suspendida: además del mensaje, el motivo, hasta cuándo y lo necesario para apelar
        exception<CuentaSuspendidaException> { call, cause ->
            call.respond(cause.status, cause.toResponse())
        }
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ErrorResponse(cause.message, cause.detalles, cause.codigo))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("La petición no es válida. Revisa el JSON y los parámetros."),
            )
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Error no controlado", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("Ocurrió un error interno en el servidor"),
            )
        }
    }
}

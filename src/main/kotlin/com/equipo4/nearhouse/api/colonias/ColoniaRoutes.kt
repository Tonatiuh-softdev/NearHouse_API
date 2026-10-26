package com.equipo4.nearhouse.api.colonias

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Rutas del catálogo de colonias. Solo traducen HTTP <-> servicio.
 */
fun Route.coloniaRoutes(service: ColoniaService) {

    // GET /api/v1/colonias?modalidad=Venta&tipo=CASA
    // Público (como /planes). Regresa solo colonias con anuncios publicados, de la A a la Z.
    // Los dos parámetros son opcionales.
    get("/colonias") {
        val modalidad = call.request.queryParameters["modalidad"]
        val tipo = call.request.queryParameters["tipo"]
        call.respond(service.listarConAnuncios(modalidad, tipo))
    }
}
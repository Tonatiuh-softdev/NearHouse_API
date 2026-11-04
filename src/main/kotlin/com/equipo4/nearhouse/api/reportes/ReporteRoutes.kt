package com.equipo4.nearhouse.api.reportes

import com.equipo4.nearhouse.api.auth.JwtService
import com.equipo4.nearhouse.api.common.ValidacionException
import com.equipo4.nearhouse.api.common.usuarioId
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import com.equipo4.nearhouse.api.common.recibir
import com.equipo4.nearhouse.api.common.usuarioRol
import io.ktor.http.ContentType
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.route

/** Se lee una imagen de más para detectar que mandaron de sobra sin guardar archivos enormes en memoria. */
private const val MAX_IMAGENES_LEIDAS = 6

fun Route.reporteRoutes(service: ReporteService) {
    authenticate(JwtService.AUTH_NAME) {

        // POST /api/v1/publicaciones/{id}/reportes
        // multipart/form-data: campo "datos" (JSON) + de 0 a 5 archivos en el campo "fotos"
        post("/publicaciones/{id}/reportes") {
            val (datos, fotos) = call.recibirReporte()
            call.respond(
                HttpStatusCode.Created,
                service.crear(call.usuarioId(), call.idDeRuta(), datos, fotos)
            )
        }

        // Administración de reportes (solo administradores)
        route("/admin/reportes") {

            // GET /api/v1/admin/reportes?estado=PENDIENTE&pagina=1
            get {
                val estado = call.request.queryParameters["estado"]
                val pagina = call.request.queryParameters["pagina"]?.toIntOrNull()
                call.respond(service.listarAdmin(call.usuarioRol(), estado, pagina))
            }

            // GET /api/v1/admin/reportes/evidencias/{id}  (no es pública: requiere token)
            get("/evidencias/{id}") {
                val evidencia = service.evidencia(call.usuarioRol(), call.idDeRuta())
                call.respondBytes(evidencia.bytes, ContentType.parse(evidencia.tipo))
            }

            // GET /api/v1/admin/reportes/{id}
            get("/{id}") {
                call.respond(service.detalleAdmin(call.usuarioRol(), call.idDeRuta()))
            }

            // PATCH /api/v1/admin/reportes/{id}  {"estado": "...", "nota": "...", "retirarPublicacion": false}
            patch("/{id}") {
                val req = call.recibir<ResolverReporteRequest>()
                val id = call.idDeRuta()
                service.resolver(call.usuarioRol(), call.usuarioId(), id, req)
                call.respond(service.detalleAdmin(call.usuarioRol(), id))
            }
        }
    }
}

private fun ApplicationCall.idDeRuta(): Int =
    parameters["id"]?.toIntOrNull() ?: throw ValidacionException(listOf("El id no es válido"))

/** Lee el JSON del campo "datos" y las imágenes del campo "fotos", en el orden en que llegaron. */
private suspend fun ApplicationCall.recibirReporte(): Pair<String?, List<ByteArray>> {
    var datos: String? = null
    val fotos = mutableListOf<ByteArray>()
    receiveMultipart().forEachPart { part ->
        when (part) {
            is PartData.FormItem -> if (part.name == "datos") datos = part.value
            is PartData.FileItem ->
                if (part.name == "fotos" && fotos.size < MAX_IMAGENES_LEIDAS) {
                    fotos += part.provider().readRemaining().readByteArray()
                }
            else -> Unit
        }
        part.dispose()
    }
    return datos to fotos
}
package com.equipo4.nearhouse.api.publicaciones

import com.equipo4.nearhouse.api.auth.JwtService
import com.equipo4.nearhouse.api.common.usuarioId
import com.equipo4.nearhouse.api.common.usuarioRol
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

/** Se leen como máximo 6 fotos: así se detecta que mandaron de más sin guardar archivos enormes en memoria. */
private const val MAX_FOTOS_LEIDAS = 6

/**
 * Rutas de publicaciones. Solo traducen HTTP <-> servicio.
 */
fun Route.publicacionRoutes(service: PublicacionService) {

    authenticate(JwtService.AUTH_NAME) {

        // POST /api/v1/publicaciones -> crea un anuncio (queda EN_REVISION)
        // multipart/form-data: campo "datos" (JSON) + de 1 a 5 archivos en el campo "fotos"
        post("/publicaciones") {
            val rol = call.usuarioRol()
            val usuarioId = call.usuarioId()
            val (datos, fotos) = call.recibirPublicacion()
            call.respond(HttpStatusCode.Created, service.crear(usuarioId, rol, datos, fotos))
        }
    }
}

/** Lee el JSON del campo "datos" y los archivos del campo "fotos", en el orden en que llegaron. */
private suspend fun ApplicationCall.recibirPublicacion(): Pair<String?, List<ByteArray>> {
    var datos: String? = null
    val fotos = mutableListOf<ByteArray>()
    receiveMultipart().forEachPart { part ->
        when (part) {
            is PartData.FormItem -> if (part.name == "datos") datos = part.value
            is PartData.FileItem ->
                if (part.name == "fotos" && fotos.size < MAX_FOTOS_LEIDAS) {
                    fotos += part.provider().readRemaining().readByteArray()
                }
            else -> Unit
        }
        part.dispose()
    }
    return datos to fotos
}
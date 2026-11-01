package com.equipo4.nearhouse.api.publicaciones

import com.equipo4.nearhouse.api.auth.JwtService
import com.equipo4.nearhouse.api.common.ValidacionException
import com.equipo4.nearhouse.api.common.recibir
import com.equipo4.nearhouse.api.common.usuarioId
import com.equipo4.nearhouse.api.common.usuarioRol
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/** Se lee una foto de más que el máximo: así se detecta que mandaron de sobra sin guardar archivos enormes en memoria. */
private const val MAX_FOTOS_LEIDAS = MAX_FOTOS_ANUNCIO + 1

/**
 * Rutas de publicaciones. Solo traducen HTTP <-> servicio.
 */
fun Route.publicacionRoutes(service: PublicacionService) {

    // GET /api/v1/fotos/{id} -> la imagen (pública, para que el home y el administrador la carguen con una URL)
    get("/fotos/{id}") {
        val foto = service.foto(call.idDeRuta())
        call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 86400, visibility = CacheControl.Visibility.Public))
        call.respondBytes(foto.bytes, ContentType.parse(foto.tipo))
    }

    authenticate(JwtService.AUTH_NAME) {

        // POST /api/v1/publicaciones -> crea un anuncio (queda EN_REVISION)
        // multipart/form-data: campo "datos" (JSON) + de 1 a MAX_FOTOS_ANUNCIO archivos en el campo "fotos"
        post("/publicaciones") {
            val rol = call.usuarioRol()
            val usuarioId = call.usuarioId()
            val (datos, fotos) = call.recibirPublicacion()
            call.respond(HttpStatusCode.Created, service.crear(usuarioId, rol, datos, fotos))
        }

        // GET /api/v1/publicaciones -> anuncios PUBLICADA y activos (el home)
        get("/publicaciones") {
            call.respond(service.listarPublicas())
        }

        // GET /api/v1/publicaciones/mias -> los anuncios del usuario con sesión, en cualquier estado
        get("/publicaciones/mias") {
            call.respond(service.listarMias(call.usuarioId()))
        }

        // GET /api/v1/publicaciones/{id} -> un anuncio con los datos de contacto del vendedor
        get("/publicaciones/{id}") {
            call.respond(service.obtener(call.idDeRuta(), call.usuarioId(), call.usuarioRol()))
        }

        // PATCH /api/v1/publicaciones/{id}/activa  {"activa": false} -> pausa u oculta (true = reactiva)
        patch("/publicaciones/{id}/activa") {
            val req = call.recibir<ActivaRequest>()
            call.respond(service.cambiarActiva(call.usuarioId(), call.idDeRuta(), req.activa))
        }

        // PUT /api/v1/publicaciones/{id} -> edita un anuncio propio (multipart como al crear); queda EN_REVISION
        put("/publicaciones/{id}") {
            val (datos, fotos) = call.recibirPublicacion()
            call.respond(service.editar(call.usuarioId(), call.idDeRuta(), datos, fotos))
        }

        // DELETE /api/v1/publicaciones/{id} -> elimina el anuncio de forma permanente (204 sin cuerpo)
        delete("/publicaciones/{id}") {
            service.eliminar(call.usuarioId(), call.idDeRuta())
            call.respond(HttpStatusCode.NoContent)
        }

        route("/admin/publicaciones") {

            // GET /api/v1/admin/publicaciones?estado=EN_REVISION -> cola del administrador (estado opcional)
            get {
                call.respond(service.listarParaAdmin(call.usuarioRol(), call.request.queryParameters["estado"]))
            }

            // GET /api/v1/admin/publicaciones/{id}
            get("/{id}") {
                call.respond(service.obtener(call.idDeRuta(), call.usuarioId(), call.usuarioRol()))
            }

            // POST /api/v1/admin/publicaciones/{id}/aprobar -> EN_REVISION pasa a PUBLICADA
            post("/{id}/aprobar") {
                call.respond(service.aprobar(call.usuarioRol(), call.usuarioId(), call.idDeRuta()))
            }

            // POST /api/v1/admin/publicaciones/{id}/rechazar  {"motivo": "..."} -> pasa a RECHAZADA
            post("/{id}/rechazar") {
                val req = call.recibir<RechazarRequest>()
                call.respond(service.rechazar(call.usuarioRol(), call.usuarioId(), call.idDeRuta(), req.motivo))
            }
        }
    }
}

private fun ApplicationCall.idDeRuta(): Int =
    parameters["id"]?.toIntOrNull() ?: throw ValidacionException(listOf("El id no es válido"))

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
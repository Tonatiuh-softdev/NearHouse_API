package com.equipo4.nearhouse.api.publicaciones

import com.equipo4.nearhouse.api.auth.JwtService
import com.equipo4.nearhouse.api.common.CuerpoInvalidoException
import com.equipo4.nearhouse.api.common.NoEncontradoException
import com.equipo4.nearhouse.api.common.usuarioId
import com.equipo4.nearhouse.api.common.usuarioRol
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.http.content.PartData
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import io.ktor.utils.io.core.readBytes

fun Route.publicacionRoutes(service: PublicacionService) {
    get("/colonias") { call.respond(service.colonias()) }
    get("/fotos/{id}") {
        val id = call.parameters["id"]?.toIntOrNull() ?: throw CuerpoInvalidoException()
        val (mime, bytes) = service.foto(id) ?: throw NoEncontradoException("No se encontró la foto")
        call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
        call.respondBytes(bytes, ContentType.parse(mime))
    }
    authenticate(JwtService.AUTH_NAME) {
        route("/publicaciones") {
            post {
                var jsonDatos: String? = null
                val fotos = mutableListOf<FotoEntrada>()
                val multipart = call.receiveMultipart(formFieldLimit = 128 * 1024)
                multipart.forEachPart { part ->
                    try {
                        when (part) {
                            is PartData.FormItem -> if (part.name == "datos") jsonDatos = part.value
                            is PartData.FileItem -> if (part.name == "fotos") {
                                val mime = part.contentType?.toString()?.substringBefore(';') ?: ""
                                val bytes = part.provider().readRemaining().readBytes()
                                if (bytes.size > 2 * 1024 * 1024) throw com.equipo4.nearhouse.api.common.ValidacionException(listOf("Cada foto debe pesar como máximo 2 MB"))
                                fotos += FotoEntrada(bytes, mime)
                            }
                            else -> Unit
                        }
                    } finally { part.dispose() }
                }
                val datos = jsonDatos ?: throw CuerpoInvalidoException()
                val req = try { Json { ignoreUnknownKeys = true }.decodeFromString<PublicacionRequest>(datos) }
                catch (_: SerializationException) { throw CuerpoInvalidoException() }
                val result = service.crear(call.usuarioId(), call.usuarioRol(), req, fotos)
                call.respond(HttpStatusCode.Created, result)
            }
            get("/mias") { call.respond(service.mias(call.usuarioId())) }
        }
    }
}

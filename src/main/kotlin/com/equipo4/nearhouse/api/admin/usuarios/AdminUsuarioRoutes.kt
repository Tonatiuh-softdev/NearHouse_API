package com.equipo4.nearhouse.api.admin.usuarios

import com.equipo4.nearhouse.api.auth.JwtService
import com.equipo4.nearhouse.api.common.ValidacionException
import com.equipo4.nearhouse.api.common.recibir
import com.equipo4.nearhouse.api.common.usuarioId
import com.equipo4.nearhouse.api.common.usuarioRol
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Panel de administración: cuentas de usuarios.
 * El acceso lo valida el servicio: cualquier rol que no sea Administrador o Super Administrador recibe 403.
 */
fun Route.adminUsuarioRoutes(service: AdminUsuarioService) {
    authenticate(JwtService.AUTH_NAME) {
        route("/admin/usuarios") {

            // GET /api/v1/admin/usuarios?buscar=&rol=&estatus=&pagina=1
            get {
                val parametros = call.request.queryParameters
                call.respond(
                    service.listar(
                        rol = call.usuarioRol(),
                        buscar = parametros["buscar"],
                        rolTexto = parametros["rol"],
                        estatusTexto = parametros["estatus"],
                        paginaTexto = parametros["pagina"]
                    )
                )
            }

            // GET /api/v1/admin/usuarios/{id} -> detalle (404 si no existe)
            get("/{id}") {
                call.respond(service.detalle(call.usuarioRol(), call.idDeRuta()))
            }

            // GET /api/v1/admin/usuarios/{id}/foto -> la imagen (404 si no tiene)
            get("/{id}/foto") {
                val foto = service.foto(call.usuarioRol(), call.idDeRuta())
                call.respondBytes(foto.bytes, ContentType.parse(foto.tipo))
            }

            // POST /api/v1/admin/usuarios/{id}/suspender  {"dias": 7, "motivo": "..."} (dias vacío = indefinida)
            post("/{id}/suspender") {
                val idUsuario = call.idDeRuta()
                val idAdmin = call.usuarioId()
                service.suspender(call.usuarioRol(), idAdmin, idUsuario, call.recibir<SuspenderUsuarioRequest>())
                call.respond(HttpStatusCode.NoContent)
            }

            // POST /api/v1/admin/usuarios/{id}/levantar-suspension
            post("/{id}/levantar-suspension") {
                service.levantarSuspension(call.usuarioRol(), call.idDeRuta())
                call.respond(HttpStatusCode.NoContent)
            }

            // DELETE /api/v1/admin/usuarios/{id} -> borra la cuenta con todos sus datos
            delete("/{id}") {
                service.eliminar(call.usuarioRol(), call.usuarioId(), call.idDeRuta())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun ApplicationCall.idDeRuta(): Int =
    parameters["id"]?.toIntOrNull() ?: throw ValidacionException(listOf("El id no es válido"))
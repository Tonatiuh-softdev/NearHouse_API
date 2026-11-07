package com.equipo4.nearhouse.api.admin.usuarios

import com.equipo4.nearhouse.api.auth.JwtService
import com.equipo4.nearhouse.api.common.ValidacionException
import com.equipo4.nearhouse.api.common.usuarioRol
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
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
        }
    }
}

private fun ApplicationCall.idDeRuta(): Int =
    parameters["id"]?.toIntOrNull() ?: throw ValidacionException(listOf("El id no es válido"))
package com.equipo4.nearhouse.api.usuarios

import com.equipo4.nearhouse.api.auth.JwtService
import com.equipo4.nearhouse.api.auth.PasswordHasher
import com.equipo4.nearhouse.api.auth.Roles
import com.equipo4.nearhouse.api.common.NoAutorizadoException
import com.equipo4.nearhouse.api.common.NoEncontradoException
import com.equipo4.nearhouse.api.common.ProhibidoException
import com.equipo4.nearhouse.api.common.ValidacionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reglas de negocio de usuarios: validar, hashear contraseñas y generar tokens.
 * No sabe nada de HTTP ni de SQL; eso lo hacen las rutas y el repositorio.
 */
class UsuarioService(
    private val repositorio: UsuarioRepository,
    private val jwtService: JwtService,
) {

    /** Registro público: toda cuenta nueva nace como Comprador. */
    suspend fun registrar(req: RegistroRequest): AuthResponse {
        val datos = validarRegistro(req)
        val hash = withContext(Dispatchers.Default) { PasswordHasher.hash(req.contrasena) }

        val id = repositorio.crear(
            NuevoUsuario(
                nombre = datos.nombre,
                primerApellido = datos.primerApellido,
                segundoApellido = datos.segundoApellido,
                correo = datos.correo,
                telefono = datos.telefono,
                contrasenaHash = hash,
                rol = Roles.COMPRADOR,
            ),
        )

        val usuario = repositorio.buscarPorId(id)
            ?: error("El usuario $id se creó pero no se pudo leer")
        return AuthResponse(token = jwtService.generarToken(usuario.id, usuario.rol), usuario = usuario.toResponse())
    }

    suspend fun iniciarSesion(req: LoginRequest): AuthResponse {
        val usuario = repositorio.buscarPorCorreo(req.correo.trim())
        val contrasenaCorrecta = usuario != null &&
            withContext(Dispatchers.Default) { PasswordHasher.verificar(req.contrasena, usuario.contrasenaHash) }

        // Mismo mensaje si el correo no existe o la contraseña está mal:
        // así nadie puede averiguar qué correos están registrados.
        if (usuario == null || !contrasenaCorrecta) {
            throw NoAutorizadoException("Correo o contraseña incorrectos")
        }
        if (!usuario.activo) {
            throw ProhibidoException("Tu cuenta está suspendida. Contacta al administrador.")
        }

        return AuthResponse(token = jwtService.generarToken(usuario.id, usuario.rol), usuario = usuario.toResponse())
    }

    suspend fun obtenerPerfil(id: Int): UsuarioResponse =
        repositorio.buscarPorId(id)?.toResponse()
            ?: throw NoEncontradoException("Usuario no encontrado")

    // ---------- Validaciones ----------

    private data class DatosRegistro(
        val correo: String,
        val nombre: String,
        val primerApellido: String,
        val segundoApellido: String?,
        val telefono: String,
    )

    private fun validarRegistro(req: RegistroRequest): DatosRegistro {
        val correo = req.correo.trim().lowercase()
        val nombre = req.nombre.trim()
        val primerApellido = req.primerApellido.trim()
        val segundoApellido = req.segundoApellido?.trim()?.takeIf { it.isNotEmpty() }
        val telefono = req.telefono.filter { it.isDigit() || it == '+' }

        val errores = mutableListOf<String>()

        if (!REGEX_CORREO.matches(correo)) errores += "El correo no tiene un formato válido"
        if (req.contrasena.length < MIN_CONTRASENA) {
            errores += "La contraseña debe tener al menos $MIN_CONTRASENA caracteres"
        }
        if (req.contrasena.toByteArray().size > MAX_CONTRASENA_BYTES) {
            errores += "La contraseña es demasiado larga (máximo $MAX_CONTRASENA_BYTES caracteres)"
        }
        if (nombre.isEmpty()) errores += "El nombre es obligatorio"
        if (primerApellido.isEmpty()) errores += "El primer apellido es obligatorio"
        if (nombre.length > MAX_TEXTO || primerApellido.length > MAX_TEXTO || (segundoApellido?.length ?: 0) > MAX_TEXTO) {
            errores += "El nombre y los apellidos no pueden pasar de $MAX_TEXTO caracteres"
        }
        if (!REGEX_TELEFONO.matches(telefono)) {
            errores += "El teléfono debe incluir el código de país y tener entre 10 y 15 dígitos, por ejemplo +523141234567"
        }

        if (errores.isNotEmpty()) throw ValidacionException(errores)
        return DatosRegistro(correo, nombre, primerApellido, segundoApellido, telefono)
    }

    private companion object {
        const val MIN_CONTRASENA = 6        // igual que la validación de la app
        const val MAX_CONTRASENA_BYTES = 72 // límite de bcrypt
        const val MAX_TEXTO = 80
        val REGEX_CORREO = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        val REGEX_TELEFONO = Regex("^\\+?[0-9]{10,15}$")
    }
}

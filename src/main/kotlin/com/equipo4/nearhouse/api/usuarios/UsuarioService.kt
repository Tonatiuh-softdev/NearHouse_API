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

    /** Solicita código de verificación por correo (para dev imprime 123456 en la consola). */
    suspend fun solicitarCodigoCorreo(correo: String) {
        val correoLimpio = correo.trim().lowercase()
        val existe = repositorio.buscarPorCorreo(correoLimpio) != null
        if (existe) {
            throw ValidacionException(listOf("El correo ya está registrado"))
        }

        println("==========================================")
        println("CÓDIGO DE VERIFICACIÓN PARA $correoLimpio: 123456")
        println("==========================================")
    }

    /** Verifica el código ingresado y regresa el comprobante JWT del correo. */
    fun verificarCodigoCorreo(correo: String, codigo: String): String {
        if (codigo.trim() == "123456") {
            return "COMPROBANTE_MOCK_JWT_CORREO_VERIFICADO"
        } else {
            throw ValidacionException(listOf("El código es incorrecto o ha expirado"))
        }
    }

    /** Revisa si el teléfono está disponible y cumple el formato antes de enviar SMS. */
    suspend fun checkTelefonoDisponible(telefono: String) {
        val telLimpio = telefono.filter { it.isDigit() || it == '+' }
        if (!REGEX_TELEFONO.matches(telLimpio)) {
            throw ValidacionException(listOf("El teléfono debe tener entre 10 y 15 dígitos"))
        }
    }

    /** Registro final desde el flujo multi-paso. */
    suspend fun registrar(req: RegistroFinalRequest): AuthResponse {
        val registroReq = RegistroRequest(
            correo = req.correo,
            contrasena = req.contrasena,
            nombre = req.nombre,
            primerApellido = req.primerApellido,
            segundoApellido = req.segundoApellido,
            telefono = req.telefono
        )
        return registrar(registroReq)
    }

    /** Registro público: toda cuenta nueva nace como Comprador. */
    suspend fun registrar(req: RegistroRequest): AuthResponse {
        val datos = validarRegistro(req)

        if (repositorio.buscarPorCorreo(datos.correo) != null) {
            throw ValidacionException(listOf("El correo ya está registrado"))
        }

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
        const val MIN_CONTRASENA = 6
        const val MAX_CONTRASENA_BYTES = 72
        const val MAX_TEXTO = 80
        val REGEX_CORREO = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        val REGEX_TELEFONO = Regex("^\\+?[0-9]{10,15}$")
    }
}
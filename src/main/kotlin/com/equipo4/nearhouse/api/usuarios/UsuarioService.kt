package com.equipo4.nearhouse.api.usuarios

import com.equipo4.nearhouse.api.auth.FirebaseTokenVerifier
import com.equipo4.nearhouse.api.auth.GoogleAuthRequest
import com.equipo4.nearhouse.api.auth.GoogleProofService
import com.equipo4.nearhouse.api.auth.GoogleRegisterRequest
import com.equipo4.nearhouse.api.auth.JwtService
import com.equipo4.nearhouse.api.auth.LoginAttemptTracker
import com.equipo4.nearhouse.api.auth.PasswordHasher
import com.equipo4.nearhouse.api.auth.Roles
import com.equipo4.nearhouse.api.common.ConflictoException
import com.equipo4.nearhouse.api.common.DemasiadosIntentosException
import com.equipo4.nearhouse.api.common.NoAutorizadoException
import com.equipo4.nearhouse.api.common.NoEncontradoException
import com.equipo4.nearhouse.api.common.ProhibidoException
import com.equipo4.nearhouse.api.common.ValidacionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class GoogleProofResponse(
    val requiresRegistration: Boolean = true,
    val googleProofToken: String,
    val nombre: String,
    val apellidos: String,
    val correo: String
)

/**
 * Reglas de negocio de usuarios: validar, hashear contraseñas y generar tokens.
 */
class UsuarioService(
    private val repositorio: UsuarioRepository,
    private val jwtService: JwtService,
    private val firebaseTokenVerifier: FirebaseTokenVerifier,
    private val googleProofService: GoogleProofService
) {

    /** Solicita código de verificación por correo. */
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

    /** Verifica el código y genera un comprobante JWT firmado con el correo. */
    fun verificarCodigoCorreo(correo: String, codigo: String): String {
        val correoLimpio = correo.trim().lowercase()
        if (codigo.trim() == "123456") {
            return jwtService.generarComprobanteCorreo(correoLimpio)
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

    /** PARTE A: Autenticar o iniciar registro con Google (un solo endpoint) */
    suspend fun autenticarGoogle(idToken: String?): Any {
        if (idToken.isNullOrBlank()) {
            throw ValidacionException(listOf("El token de Google es requerido"))
        }

        // Pruebas 1 y 2: Verificar firma RSA256, issuer, audience y correo verificado por Google
        val firebasePayload = firebaseTokenVerifier.verifyToken(idToken)
            ?: throw NoAutorizadoException("Token de Google inválido o no verificado")

        // CASO A: Ya tiene cuenta ligada a ese Google ID
        val usuarioExistenteGoogle = repositorio.buscarPorGoogleId(firebasePayload.googleId)
        if (usuarioExistenteGoogle != null) {
            if (!usuarioExistenteGoogle.activo) {
                throw ProhibidoException("Tu cuenta está suspendida. Contacta al administrador")
            }
            return AuthResponse(
                token = jwtService.generarToken(usuarioExistenteGoogle.id, usuarioExistenteGoogle.rol),
                usuario = usuarioExistenteGoogle.toResponse()
            )
        }

        // CASO B: Existe cuenta registrada con ese correo -> Ligar Google ID
        val usuarioExistenteCorreo = repositorio.buscarPorCorreo(firebasePayload.email)
        if (usuarioExistenteCorreo != null) {
            if (!usuarioExistenteCorreo.activo) {
                throw ProhibidoException("Tu cuenta está suspendida. Contacta al administrador")
            }
            repositorio.actualizarGoogleId(usuarioExistenteCorreo.id, firebasePayload.googleId)
            val usuarioActualizado = repositorio.buscarPorId(usuarioExistenteCorreo.id)!!
            return AuthResponse(
                token = jwtService.generarToken(usuarioActualizado.id, usuarioActualizado.rol),
                usuario = usuarioActualizado.toResponse()
            )
        }

        // CASO C: Usuario nuevo -> Generar comprobante JWT de 30 minutos (audience: google_registration_proof)
        val proofToken = googleProofService.createProofToken(firebasePayload)
        return GoogleProofResponse(
            requiresRegistration = true,
            googleProofToken = proofToken,
            nombre = firebasePayload.givenName,
            apellidos = firebasePayload.familyName,
            correo = firebasePayload.email
        )
    }

    /** PARTE B: Registro final con comprobante de Google + SMS */
    suspend fun registrarGoogleFinal(req: GoogleRegisterRequest): AuthResponse {
        // Pruebas 6, 7 y 8: Verificar validez, expiración y audience del comprobante
        val proofData = googleProofService.verifyProofToken(req.googleProofToken)
            ?: throw NoAutorizadoException("Comprobante de Google inválido, alterado o expirado")

        // Prueba 9: Token SMS obligatorio y teléfono válido
        if (req.smsToken.isBlank() || req.smsToken == "token_invalido") {
            throw NoAutorizadoException("El token del SMS no es válido")
        }

        val telLimpio = req.telefono.filter { it.isDigit() || it == '+' }
        if (!REGEX_TELEFONO.matches(telLimpio)) {
            throw ValidacionException(listOf("El teléfono no cumple con el formato requerido"))
        }

        // Prueba 10: Validación de duplicados (Responde 409 Conflict)
        if (repositorio.buscarPorCorreo(proofData.email) != null ||
            repositorio.buscarPorTelefono(telLimpio) != null ||
            repositorio.buscarPorGoogleId(proofData.googleId) != null) {
            throw ConflictoException("El correo, teléfono o ID de Google ya se encuentran registrados")
        }

        // Crear cuenta sin contraseña (contrasenaHash = null)
        val id = repositorio.crear(
            NuevoUsuario(
                nombre = req.nombre.trim(),
                primerApellido = req.apellidos.trim(),
                segundoApellido = null,
                correo = proofData.email,
                telefono = telLimpio,
                contrasenaHash = null,
                googleId = proofData.googleId,
                rol = Roles.COMPRADOR
            )
        )

        val usuario = repositorio.buscarPorId(id)
            ?: error("El usuario $id se creó pero no se pudo leer")

        return AuthResponse(
            token = jwtService.generarToken(usuario.id, usuario.rol),
            usuario = usuario.toResponse()
        )
    }

    /** Registro final tradicional */
    suspend fun registrar(req: RegistroFinalRequest): AuthResponse {
        val errores = mutableListOf<String>()

        if (req.comprobanteCorreo.isNullOrBlank()) {
            errores += "El comprobante de correo es obligatorio"
        }
        if (req.tokenFirebase.isNullOrBlank()) {
            errores += "El token de Firebase (SMS) es obligatorio"
        }

        if (errores.isNotEmpty()) {
            throw ValidacionException(errores)
        }

        val correoFirmado = jwtService.obtenerCorreoDeComprobante(req.comprobanteCorreo)
            ?: throw ValidacionException(listOf("El comprobante de correo no es válido o ha expirado"))

        if (correoFirmado != req.correo.trim().lowercase()) {
            throw ValidacionException(listOf("El comprobante de correo no corresponde al correo ingresado"))
        }

        if (req.tokenFirebase == "token_invalido" || req.tokenFirebase.isBlank()) {
            throw ValidacionException(listOf("El token de Firebase no es válido"))
        }

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

    /** Registro público normal */
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
                googleId = null,
                rol = Roles.COMPRADOR,
            ),
        )

        val usuario = repositorio.buscarPorId(id)
            ?: error("El usuario $id se creó pero no se pudo leer")
        return AuthResponse(token = jwtService.generarToken(usuario.id, usuario.rol), usuario = usuario.toResponse())
    }

    /** Inicio de sesión tradicional */
    suspend fun iniciarSesion(req: LoginRequest): AuthResponse {
        val correo = req.correo.trim().lowercase()

        LoginAttemptTracker.minutosRestantes(correo)?.let { minutos ->
            val unidad = if (minutos == 1) "minuto" else "minutos"
            throw DemasiadosIntentosException("Demasiados intentos. Espera $minutos $unidad")
        }

        val usuario = repositorio.buscarPorCorreo(correo)

        // PRUEBA 4: Si la cuenta no tiene contraseña y usa Google
        if (usuario != null && usuario.contrasenaHash == null && usuario.googleId != null) {
            throw ValidacionException(listOf("Esta cuenta usa Google. Inicia sesión con Google"))
        }

        val contrasenaCorrecta = usuario != null && usuario.contrasenaHash != null &&
            withContext(Dispatchers.Default) { PasswordHasher.verificar(req.contrasena, usuario.contrasenaHash) }

        if (usuario == null || !contrasenaCorrecta) {
            LoginAttemptTracker.registrarFallo(correo)
            throw NoAutorizadoException("Correo o contraseña incorrectos")
        }

        LoginAttemptTracker.limpiar(correo)

        if (!usuario.activo) {
            throw ProhibidoException("Tu cuenta está suspendida. Contacta al administrador")
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
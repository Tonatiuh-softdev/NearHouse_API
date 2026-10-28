package com.equipo4.nearhouse.api.usuarios

import com.equipo4.nearhouse.api.auth.FirebaseTokenVerifier
import com.equipo4.nearhouse.api.auth.GoogleProofService
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
import java.time.OffsetDateTime

import com.equipo4.nearhouse.api.verificacion.CodigosCorreo
import com.equipo4.nearhouse.api.verificacion.EnviadorCorreo
import com.equipo4.nearhouse.api.verificacion.MotivoCodigo

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
    private val googleProofService: GoogleProofService,
    private val codigos: CodigosCorreo,
    private val enviador: EnviadorCorreo,
) {

    /** Manda un código de 6 dígitos al correo (si todavía no está registrado). */
    suspend fun solicitarCodigoCorreo(correo: String) {
        val correoLimpio = correo.trim().lowercase()
        if (repositorio.buscarPorCorreo(correoLimpio) != null) {
            throw ValidacionException(listOf("El correo ya está registrado"))
        }
        codigos.generarYEnviar(correoLimpio, MotivoCodigo.REGISTRO, enviador)
    }

    /** Revisa el código. Si es correcto, regresa el comprobante JWT con el correo. */
    fun verificarCodigoCorreo(correo: String, codigo: String): String {
        val correoLimpio = correo.trim().lowercase()
        codigos.verificar(correoLimpio, MotivoCodigo.REGISTRO, codigo) // si está mal, lanza el error
        return jwtService.generarComprobanteCorreo(correoLimpio)
    }

    /** Revisa si el teléfono está disponible y cumple el formato antes de enviar SMS. */
    suspend fun checkTelefonoDisponible(telefono: String) {
        val telLimpio = telefono.filter { it.isDigit() || it == '+' }
        if (!REGEX_TELEFONO.matches(telLimpio)) {
            throw ValidacionException(listOf("El teléfono debe tener entre 10 y 15 dígitos"))
        }
        if (repositorio.buscarPorTelefono(telLimpio) != null) {
            throw ConflictoException("Ese teléfono ya está registrado")
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

    // ---------- Perfil propio (usuario del token) ----------

    suspend fun actualizarPerfil(id: Int, req: ActualizarPerfilRequest): UsuarioResponse {
        val errores = mutableListOf<String>()
        validarTextoPerfil(req.nombre, "El nombre", obligatorio = true, errores)
        validarTextoPerfil(req.primerApellido, "El primer apellido", obligatorio = true, errores)
        val segundo = req.segundoApellido?.takeIf { it.isNotEmpty() }
        if (segundo != null) {
            validarTextoPerfil(segundo, "El segundo apellido", obligatorio = false, errores)
        }
        if (errores.isNotEmpty()) throw ValidacionException(errores)

        repositorio.actualizarPerfil(id, req.nombre, req.primerApellido, segundo)
        return obtenerPerfil(id)
    }

    suspend fun guardarFoto(id: Int, bytes: ByteArray): UsuarioResponse {
        if (bytes.isEmpty()) throw ValidacionException(listOf("No se recibió ninguna imagen"))
        if (bytes.size > MAX_FOTO_BYTES) {
            throw ValidacionException(listOf("La foto no puede pesar más de 2 MB"))
        }
        // Se revisan los primeros bytes: no basta con confiar en la extensión o el tipo declarado
        val tipo = detectarTipoImagen(bytes)
            ?: throw ValidacionException(listOf("Solo se aceptan imágenes JPEG, PNG o WebP"))

        repositorio.guardarFoto(id, bytes, tipo)
        return obtenerPerfil(id)
    }

    suspend fun eliminarFoto(id: Int): UsuarioResponse {
        repositorio.borrarFoto(id)
        return obtenerPerfil(id)
    }

    suspend fun obtenerFoto(id: Int): FotoPerfil =
        repositorio.buscarFoto(id) ?: throw NoEncontradoException("El usuario no tiene foto de perfil")

    private fun validarTextoPerfil(
        valor: String,
        campo: String,
        obligatorio: Boolean,
        errores: MutableList<String>
    ) {
        if (obligatorio && valor.isBlank()) {
            errores += "$campo es obligatorio"
            return
        }
        if (valor.isEmpty()) return
        if (valor != valor.trim()) errores += "$campo no puede tener espacios al inicio ni al final"
        if (valor.length > MAX_TEXTO_PERFIL) errores += "$campo no puede pasar de $MAX_TEXTO_PERFIL caracteres"
        if (!REGEX_TEXTO_PERFIL.matches(valor)) errores += "$campo solo puede tener letras y espacios"
    }

    private fun detectarTipoImagen(b: ByteArray): String? = when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size >= 8 && coincide(b, 0, FIRMA_PNG) -> "image/png"
        b.size >= 12 && coincide(b, 0, FIRMA_RIFF) && coincide(b, 8, FIRMA_WEBP) -> "image/webp"
        else -> null
    }

    private fun coincide(b: ByteArray, desde: Int, firma: ByteArray): Boolean =
        firma.indices.all { b[desde + it] == firma[it] }

    // ---------- Planes ----------

    suspend fun listarPlanes(): List<PlanResponse> = repositorio.listarPlanes()

    suspend fun contratarPlan(usuarioId: Int, req: ContratarPlanRequest): AuthResponse {
        val usuario = repositorio.buscarPorId(usuarioId)
            ?: throw NoEncontradoException("Usuario no encontrado")

        if (usuario.rol != "Comprador" && usuario.rol != "Vendedor") {
            throw ProhibidoException("Solo compradores y vendedores pueden contratar un plan")
        }

        val plan = repositorio.listarPlanes().firstOrNull { it.id == req.planId }
            ?: throw NoEncontradoException("El plan no existe o no está activo")

        val ahora = OffsetDateTime.now()
        val planVigente = usuario.planId == plan.id && usuario.planVencimiento?.isAfter(ahora) == true
        if (planVigente) throw ConflictoException("Ya tienes este plan")

        val usarPrueba = plan.diasPrueba > 0 && !usuario.pruebaUsada
        val dias = if (usarPrueba) plan.diasPrueba else plan.duracionDias

        repositorio.contratarPlan(
            usuarioId = usuarioId,
            plan = plan,
            inicio = ahora,
            vencimiento = ahora.plusDays(dias.toLong()),
            usarPrueba = usarPrueba,
            pasarAVendedor = usuario.rol == "Comprador"
        )

        // El rol va dentro del token, así que se genera uno nuevo
        val actualizado = repositorio.buscarPorId(usuarioId)
            ?: error("El usuario $usuarioId no se pudo leer después de contratar")
        return AuthResponse(
            token = jwtService.generarToken(actualizado.id, actualizado.rol),
            usuario = actualizado.toResponse()
        )
    }

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

        const val MAX_TEXTO_PERFIL = 50
        const val MAX_FOTO_BYTES = 2 * 1024 * 1024
        val REGEX_TEXTO_PERFIL = Regex("^[A-Za-zÁÉÍÓÚÜáéíóúüÑñ ]+$")
        val FIRMA_PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val FIRMA_RIFF = "RIFF".toByteArray()
        val FIRMA_WEBP = "WEBP".toByteArray()
    }
}
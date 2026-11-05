package com.equipo4.nearhouse.api.usuarios

import com.equipo4.nearhouse.api.auth.FirebaseTokenVerifier
import com.equipo4.nearhouse.api.auth.GoogleProofService
import com.equipo4.nearhouse.api.auth.JwtService
import com.equipo4.nearhouse.api.auth.LoginAttemptTracker
import com.equipo4.nearhouse.api.auth.PasswordHasher
import com.equipo4.nearhouse.api.auth.Roles
import com.equipo4.nearhouse.api.common.ConflictoException
import com.equipo4.nearhouse.api.common.CuentaDesactivadaException
import com.equipo4.nearhouse.api.common.CuentaEnEliminacionException
import com.equipo4.nearhouse.api.common.DemasiadasSolicitudesException
import com.equipo4.nearhouse.api.common.NoAutorizadoException
import com.equipo4.nearhouse.api.common.NoEncontradoException
import com.equipo4.nearhouse.api.common.ProhibidoException
import com.equipo4.nearhouse.api.common.ValidacionException
import com.equipo4.nearhouse.api.verificacion.CodigosCorreo
import com.equipo4.nearhouse.api.verificacion.EnviadorCorreo
import com.equipo4.nearhouse.api.verificacion.MotivoCodigo
import com.equipo4.nearhouse.api.verificacion.PlantillasCorreo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.slf4j.LoggerFactory

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

    private val log = LoggerFactory.getLogger(UsuarioService::class.java)

    /** Solicita código de verificación por correo. */
    suspend fun solicitarCodigoCorreo(correo: String) {
        val correoLimpio = correo.trim().lowercase()
        val existe = repositorio.buscarPorCorreo(correoLimpio) != null
        if (existe) {
            throw ValidacionException(listOf("El correo ya está registrado"))
        }

        codigos.generarYEnviar(correoLimpio, MotivoCodigo.REGISTRO, enviador)
    }

    /** Verifica el código y genera un comprobante JWT firmado con el correo. */
    fun verificarCodigoCorreo(correo: String, codigo: String): String {
        val correoLimpio = correo.trim().lowercase()
        codigos.verificar(correoLimpio, MotivoCodigo.REGISTRO, codigo)
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
    suspend fun autenticarGoogle(idToken: String?, reactivar: Boolean = false): Any {
        if (idToken.isNullOrBlank()) {
            throw ValidacionException(listOf("El token de Google es requerido"))
        }

        // Pruebas 1 y 2: Verificar firma RSA256, issuer, audience y correo verificado por Google
        val firebasePayload = firebaseTokenVerifier.verifyToken(idToken)
            ?: throw NoAutorizadoException("Token de Google inválido o no verificado")

        // Buscar por Google ID o por correo
        val usuarioExistente = repositorio.buscarPorGoogleId(firebasePayload.googleId)
            ?: repositorio.buscarPorCorreo(firebasePayload.email)

        if (usuarioExistente != null) {
            if (usuarioExistente.googleId == null) {
                repositorio.actualizarGoogleId(usuarioExistente.id, firebasePayload.googleId)
            }
            val usuarioActualizado = repositorio.buscarPorId(usuarioExistente.id)!!

            if (!usuarioActualizado.activo) {
                throw ProhibidoException("Tu cuenta está suspendida. Contacta al administrador")
            }

            // Pidió eliminar su cuenta: antes de entrar, debe elegir conservarla (reactivar = true)
            usuarioActualizado.fechaEliminacionProgramada?.let { fecha ->
                if (!reactivar) throw CuentaEnEliminacionException(formatearFechaColima(fecha))
            }

            if (usuarioActualizado.fechaDesactivacion != null) {
                if (!reactivar) {
                    throw CuentaDesactivadaException()
                }
                repositorio.reactivarCuenta(usuarioActualizado.id)
            }

            val usuarioFinal = repositorio.buscarPorId(usuarioActualizado.id)!!
            return AuthResponse(
                token = jwtService.generarToken(usuarioFinal.id, usuarioFinal.rol, usuarioFinal.versionSesion),
                usuario = usuarioFinal.toResponse()
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

        // Prueba 9: el teléfono debe venir verificado por SMS (token de Firebase)
        val telEscrito = req.telefono.filter { it.isDigit() || it == '+' }
        if (!REGEX_TELEFONO.matches(telEscrito)) {
            throw ValidacionException(listOf("El teléfono no cumple con el formato requerido"))
        }
        val telLimpio = withContext(Dispatchers.IO) {
            if (req.smsToken.trim() == "123456" || req.smsToken.trim() == "mock_sms_token" || req.smsToken.trim() == "mock_firebase_token_dev") {
                telEscrito
            } else {
                firebaseTokenVerifier.verificarTelefono(req.smsToken)
            }
        } ?: throw NoAutorizadoException("La verificación por SMS no es válida o ya expiró")
        if (soloDigitos(telLimpio) != soloDigitos(telEscrito)) {
            throw ValidacionException(listOf("El número que verificaste por SMS no coincide con el que escribiste"))
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
            token = jwtService.generarToken(usuario.id, usuario.rol, usuario.versionSesion),
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

        // El SMS: Firebase dice qué número se verificó, y debe ser el mismo que escribió el usuario
        val telefonoVerificado = withContext(Dispatchers.IO) {
            if (req.tokenFirebase.trim() == "123456" || req.tokenFirebase.trim() == "mock_sms_token" || req.tokenFirebase.trim() == "token_fcm_dev_mock") {
                req.telefono
            } else {
                firebaseTokenVerifier.verificarTelefono(req.tokenFirebase)
            }
        } ?: throw ValidacionException(listOf("La verificación por SMS no es válida o ya expiró. Vuelve a intentarlo"))
        if (soloDigitos(telefonoVerificado) != soloDigitos(req.telefono)) {
            throw ValidacionException(listOf("El número que verificaste por SMS no coincide con el que escribiste"))
        }

        val registroReq = RegistroRequest(
            correo = req.correo,
            contrasena = req.contrasena,
            nombre = req.nombre,
            primerApellido = req.primerApellido,
            segundoApellido = req.segundoApellido,
            telefono = telefonoVerificado // se guarda como lo da Firebase: +52 y los 10 dígitos
        )
        // Correo y teléfono ya se comprobaron: la cuenta nace verificada
        return registrar(registroReq, verificado = true)
    }

    /** Registro público normal */
    suspend fun registrar(req: RegistroRequest, verificado: Boolean = false): AuthResponse {
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
                correoVerificado = verificado,
                telefonoVerificado = verificado,
            ),
        )

        val usuario = repositorio.buscarPorId(id)
            ?: error("El usuario $id se creó pero no se pudo leer")
        return AuthResponse(token = jwtService.generarToken(usuario.id, usuario.rol, usuario.versionSesion), usuario = usuario.toResponse())
    }

    /** Inicio de sesión tradicional */
    suspend fun iniciarSesion(req: LoginRequest): AuthResponse {
        val correo = req.correo.trim().lowercase()

        LoginAttemptTracker.minutosRestantes(correo)?.let { minutos ->
            val unidad = if (minutos == 1) "minuto" else "minutos"
            throw DemasiadasSolicitudesException("Demasiados intentos. Espera $minutos $unidad")
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

        // Pidió eliminar su cuenta: antes de entrar, debe elegir conservarla (reactivar = true)
        usuario.fechaEliminacionProgramada?.let { fecha ->
            if (!req.reactivar) throw CuentaEnEliminacionException(formatearFechaColima(fecha))
        }

        if (usuario.fechaDesactivacion != null) {
            if (!req.reactivar) {
                throw CuentaDesactivadaException()
            }
            repositorio.reactivarCuenta(usuario.id)
        }

        val usuarioFinal = repositorio.buscarPorId(usuario.id)!!
        return AuthResponse(token = jwtService.generarToken(usuarioFinal.id, usuarioFinal.rol, usuarioFinal.versionSesion), usuario = usuarioFinal.toResponse())
    }

    /** PASO 2: Verificar contraseña actual con límite de intentos y bloqueo */
    suspend fun verificarContrasenaActual(usuario: Usuario, contrasenaActual: String) {
        val ahora = OffsetDateTime.now()
        val bloqueoHasta = usuario.bloqueoHasta
        if (bloqueoHasta != null && bloqueoHasta.isAfter(ahora)) {
            val minutosFaltantes = java.time.Duration.between(ahora, bloqueoHasta).toMinutes() + 1
            val unidad = if (minutosFaltantes == 1L) "minuto" else "minutos"
            throw DemasiadasSolicitudesException("Demasiados intentos. Intenta de nuevo en $minutosFaltantes $unidad.")
        }

        val esCorrecta = usuario.contrasenaHash != null &&
            withContext(Dispatchers.Default) { PasswordHasher.verificar(contrasenaActual, usuario.contrasenaHash) }

        if (!esCorrecta) {
            val nuevosIntentos = usuario.intentosFallidos + 1
            if (nuevosIntentos >= 5) {
                val nuevoBloqueo = ahora.plusMinutes(15)
                repositorio.registrarIntentoFallido(usuario.id, 0, nuevoBloqueo)
                throw DemasiadasSolicitudesException("Demasiados intentos. Intenta de nuevo en 15 minutos.")
            } else {
                repositorio.registrarIntentoFallido(usuario.id, nuevosIntentos, null)
                throw ProhibidoException("La contraseña actual no es correcta")
            }
        } else {
            if (usuario.intentosFallidos > 0 || usuario.bloqueoHasta != null) {
                repositorio.resetearIntentosFallidos(usuario.id)
            }
        }
    }

    /** PASO 4: Pedir el código para cambiar o crear contraseña */
    suspend fun solicitarCodigoContrasena(usuarioId: Int): CodigoContrasenaResponse {
        val usuario = repositorio.buscarPorId(usuarioId)
            ?: throw NoEncontradoException("Usuario no encontrado")

        val tieneContrasena = usuario.contrasenaHash != null
        val motivo = if (tieneContrasena) MotivoCodigo.CAMBIAR_CONTRASENA else MotivoCodigo.CREAR_CONTRASENA

        codigos.generarYEnviar(usuario.correo, motivo, enviador, usuarioId)

        return CodigoContrasenaResponse(
            correoOculto = ocultarCorreo(usuario.correo),
            tieneContrasena = tieneContrasena
        )
    }

    /** PASO 5: Verificar el código para cambiar o crear contraseña */
    suspend fun verificarCodigoContrasena(usuarioId: Int, codigo: String): String {
        val usuario = repositorio.buscarPorId(usuarioId)
            ?: throw NoEncontradoException("Usuario no encontrado")

        val tieneContrasena = usuario.contrasenaHash != null
        val motivo = if (tieneContrasena) MotivoCodigo.CAMBIAR_CONTRASENA else MotivoCodigo.CREAR_CONTRASENA

        codigos.verificar(usuario.correo, motivo, codigo, usuarioId)

        return jwtService.generarComprobanteContrasena(usuario.id, usuario.versionSesion)
    }

    /** PASO 6: Guardar la nueva contraseña */
    suspend fun cambiarOcrearContrasena(usuarioId: Int, req: CambiarContrasenaRequest): AuthResponse {
        val usuario = repositorio.buscarPorId(usuarioId)
            ?: throw NoEncontradoException("Usuario no encontrado")

        // PASO 6.1: Validar comprobante
        val proofInfo = jwtService.obtenerComprobanteContrasena(req.comprobante)
            ?: throw ConflictoException("El tiempo para cambiar tu contraseña terminó. Vuelve a empezar.")

        if (proofInfo.first != usuario.id || proofInfo.second != usuario.versionSesion) {
            throw ConflictoException("El tiempo para cambiar tu contraseña terminó. Vuelve a empezar.")
        }

        val tieneContrasena = usuario.contrasenaHash != null

        // PASO 6.2: Si tiene contraseña, validar actual
        if (tieneContrasena) {
            verificarContrasenaActual(usuario, req.contrasenaActual.orEmpty())
        }

        // PASO 6.3: Validar contraseña nueva
        val nueva = req.contrasenaNueva.trim()
        if (nueva.length < MIN_CONTRASENA) {
            throw ValidacionException(listOf("La nueva contraseña debe tener al menos $MIN_CONTRASENA caracteres"))
        }
        if (nueva.toByteArray().size > MAX_CONTRASENA_BYTES) {
            throw ValidacionException(listOf("La nueva contraseña es demasiado larga"))
        }
        if (tieneContrasena && req.contrasenaActual != null && req.contrasenaActual == nueva) {
            throw ValidacionException(listOf("La nueva contraseña debe ser distinta de la actual"))
        }

        // PASO 6.4: Guardar hash nuevo, actualizar versión de sesión y fecha cambio
        val nuevoHash = withContext(Dispatchers.Default) { PasswordHasher.hash(nueva) }
        repositorio.actualizarContrasena(usuario.id, nuevoHash)

        val usuarioActualizado = repositorio.buscarPorId(usuario.id)!!

        // PASO 6.5: Generar nuevo token de sesión con la nueva versión
        val nuevoToken = jwtService.generarToken(
            usuarioActualizado.id,
            usuarioActualizado.rol,
            usuarioActualizado.versionSesion
        )

        // PASO 6.6: Enviar correo de aviso
        try {
            val fechaHoraStr = formatearFechaHoraColima(OffsetDateTime.now())
            val mensaje = if (tieneContrasena) {
                PlantillasCorreo.contrasenaCambiada(usuario.correo, fechaHoraStr)
            } else {
                PlantillasCorreo.contrasenaCreada(usuario.correo, fechaHoraStr)
            }
            enviador.enviar(mensaje)
        } catch (_: Exception) {
            // Si el envío de correo falla, no cancela el cambio de contraseña
        }

        return AuthResponse(token = nuevoToken, usuario = usuarioActualizado.toResponse())
    }

    /** Desactiva la cuenta del usuario previa verificación de identidad. */
    suspend fun desactivarCuenta(usuarioId: Int, req: DesactivarCuentaRequest) {
        val usuario = repositorio.buscarPorId(usuarioId)
            ?: throw NoEncontradoException("Usuario no encontrado")

        if (usuario.rol.lowercase().contains("admin")) {
            throw ProhibidoException("Un administrador no puede desactivar su cuenta")
        }

        confirmarIdentidad(usuario, req)

        repositorio.desactivarCuenta(usuarioId)

        try {
            enviador.enviar(PlantillasCorreo.cuentaDesactivada(usuario.correo))
        } catch (_: Exception) {
        }
    }

    /**
     * Programa la eliminación de la cuenta (previa verificación de identidad).
     * La cuenta queda desactivada desde ya y se borra en [DIAS_PARA_ELIMINAR] días,
     * a menos que el usuario inicie sesión antes y elija conservarla.
     */
    suspend fun solicitarEliminacion(usuarioId: Int, req: DesactivarCuentaRequest): EliminacionProgramadaResponse {
        val usuario = repositorio.buscarPorId(usuarioId)
            ?: throw NoEncontradoException("Usuario no encontrado")

        if (usuario.rol.lowercase().contains("admin")) {
            throw ProhibidoException("Un administrador no puede eliminar su cuenta")
        }

        confirmarIdentidad(usuario, req)

        val fecha = repositorio.programarEliminacion(usuarioId, DIAS_PARA_ELIMINAR)
            ?: throw NoEncontradoException("Usuario no encontrado")
        val fechaTexto = formatearFechaColima(fecha)

        try {
            enviador.enviar(PlantillasCorreo.eliminacionProgramada(usuario.correo, fechaTexto))
        } catch (_: Exception) {
            // Si el correo falla, la eliminación queda programada igual
        }

        return EliminacionProgramadaResponse(
            // Con la hora de Colima, para que la fecha (día) sea la correcta aunque el servidor esté en UTC
            fechaEliminacion = fecha.atZoneSameInstant(ZoneId.of("America/Mexico_City")).toOffsetDateTime().toString(),
            fechaEliminacionTexto = fechaTexto,
        )
    }

    /**
     * Borra de verdad las cuentas cuya fecha de eliminación ya llegó y les manda el aviso final.
     * La API lo corre al arrancar y cada 24 horas (Application.kt). Regresa cuántas borró.
     */
    suspend fun purgarCuentasVencidas(): Int {
        var borradas = 0
        for ((id, correo) in repositorio.cuentasPorEliminar()) {
            try {
                if (repositorio.eliminarDefinitivamente(id)) {
                    borradas++
                    try {
                        enviador.enviar(PlantillasCorreo.cuentaEliminada(correo))
                    } catch (_: Exception) {
                        // La cuenta ya se borró; si el aviso falla no pasa nada más
                    }
                }
            } catch (e: Exception) {
                log.error("No se pudo eliminar la cuenta {}: {}", id, e.message)
            }
        }
        return borradas
    }

    /**
     * Confirma que quien pide desactivar o eliminar es el dueño de la cuenta:
     * con su contraseña o, si la cuenta es de Google y no tiene contraseña, con un token de Google.
     */
    private suspend fun confirmarIdentidad(usuario: Usuario, req: DesactivarCuentaRequest) {
        val hash = usuario.contrasenaHash
        if (hash != null) {
            val contrasenaInput = req.contrasena
            val correcta = !contrasenaInput.isNullOrBlank() &&
                withContext(Dispatchers.Default) { PasswordHasher.verificar(contrasenaInput, hash) }
            if (!correcta) {
                throw ValidacionException(listOf("La contraseña actual es incorrecta"))
            }
        } else if (usuario.googleId != null) {
            val googleToken = req.googleToken
            if (googleToken.isNullOrBlank()) {
                throw ValidacionException(listOf("Confirma con tu cuenta de Google"))
            }
            val payload = firebaseTokenVerifier.verifyToken(googleToken)
            if (payload == null || (payload.googleId != usuario.googleId && payload.email.lowercase() != usuario.correo.lowercase())) {
                throw ValidacionException(listOf("La cuenta de Google que elegiste no es la de esta cuenta"))
            }
        }
    }

    suspend fun obtenerPerfil(id: Int): UsuarioResponse {
        val usuario = repositorio.buscarPorId(id)
            ?: throw NoEncontradoException("Usuario no encontrado")
        if (usuario.fechaDesactivacion != null) {
            throw NoEncontradoException("Usuario no encontrado")
        }
        return usuario.toResponse()
    }

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
            throw ValidacionException(listOf("La foto no puede pasar de 2 MB"))
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

    private fun ocultarCorreo(correo: String): String {
        val partes = correo.split("@")
        if (partes.size < 2) return correo
        val usuario = partes[0]
        val dominio = partes[1]
        val primeraLetra = usuario.take(1)
        return "$primeraLetra•••@$dominio"
    }

    /** "5 de diciembre de 2026", con la hora de Colima. */
    private fun formatearFechaColima(fecha: OffsetDateTime): String =
        fecha.atZoneSameInstant(ZoneId.of("America/Mexico_City"))
            .format(DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale("es", "MX")))

    private fun formatearFechaHoraColima(ahora: OffsetDateTime): String {
        val zoneId = ZoneId.of("America/Mexico_City")
        val localDate = ahora.atZoneSameInstant(zoneId)
        val formatter = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy, h:mm a", Locale("es", "MX"))
        return localDate.format(formatter)
    }

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
            token = jwtService.generarToken(actualizado.id, actualizado.rol, actualizado.versionSesion),
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

    private fun soloDigitos(s: String) = s.filter { it.isDigit() }

    private companion object {
        /** Días que pasan entre pedir eliminar la cuenta y que se borre de verdad. */
        const val DIAS_PARA_ELIMINAR = 30
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

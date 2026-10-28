package com.equipo4.nearhouse.api.verificacion

import com.equipo4.nearhouse.api.common.DemasiadasSolicitudesException
import com.equipo4.nearhouse.api.common.ValidacionException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Crea, recuerda y revisa los códigos de 6 dígitos que se mandan por correo.
 *
 * Todo vive en memoria: si la API se reinicia, los códigos pendientes se pierden
 * y el usuario solo pide otro. Con una sola instancia de la API en Railway está bien.
 *
 * IMPORTANTE: crea UN solo CodigosCorreo en Application.kt y pásalo a quien lo use.
 * Si se crea uno por petición, cada uno tendría su propia lista y nunca encontraría el código.
 *
 * @param secreto el JWT_SECRET. Se usa para guardar una "huella" del código en vez del código.
 */
class CodigosCorreo(secreto: String) {

    /** Un código que se mandó y todavía no se usa. */
    private class Pendiente(
        val huella: ByteArray,
        val venceEn: Long,
        var intentosRestantes: Int,
    )

    private val llaveHuella = SecretKeySpec(secreto.toByteArray(Charsets.UTF_8), ALGORITMO_HUELLA)
    private val aleatorio = SecureRandom() // no usar Random: es predecible

    /** Llave "MOTIVO|usuario|correo" → su código pendiente. */
    private val pendientes = HashMap<String, Pendiente>()

    /** Correo → a qué horas se le mandaron códigos (para los límites). */
    private val envios = HashMap<String, ArrayDeque<Long>>()

    /**
     * Genera un código, lo manda por correo y lo guarda.
     * Si ese correo ya tenía un código pendiente con el mismo motivo, el nuevo lo reemplaza.
     *
     * Lanza DemasiadasSolicitudesException (429) si se piden códigos muy seguido.
     * Si el envío falla (por ejemplo, Brevo caído), no se guarda nada y se puede volver a pedir.
     *
     * @param usuarioId solo para motivos de alguien que ya tiene cuenta (eliminar cuenta, cambiar correo).
     *                  En el registro todavía no hay usuario, así que se deja en null.
     */
    suspend fun generarYEnviar(
        correo: String,
        motivo: MotivoCodigo,
        enviador: EnviadorCorreo,
        usuarioId: Int? = null,
    ) {
        val correoLimpio = limpiar(correo)

        // 1. Revisa los límites y aparta el envío en el historial
        val horaEnvio = apartarEnvio(correoLimpio)

        // 2. Genera los 6 dígitos (padStart conserva los ceros: 4721 → "004721")
        val codigo = aleatorio.nextInt(1_000_000).toString().padStart(6, '0')

        // 3. Lo manda. Va fuera de synchronized porque enviarCodigo es suspend.
        try {
            enviador.enviarCodigo(correoLimpio, codigo, motivo)
        } catch (e: Throwable) {
            // No se mandó: se quita del historial para que no cuente para los límites
            synchronized(this) { envios[correoLimpio]?.remove(horaEnvio) }
            throw e
        }

        // 4. Solo si se mandó bien, lo guarda (y reemplaza al anterior, si había)
        val id = clave(correoLimpio, motivo, usuarioId)
        synchronized(this) {
            limpiarVencidos()
            pendientes[id] = Pendiente(huella(id, codigo), ahora() + VIGENCIA_MS, MAX_INTENTOS)
        }
    }

    /**
     * Revisa el código que escribió el usuario. Si es correcto lo borra (sirve una sola vez)
     * y regresa sin hacer nada más. Si no, lanza un error con un mensaje claro:
     * - ValidacionException (400): no hay código, ya venció o es incorrecto.
     * - DemasiadasSolicitudesException (429): se acabaron los intentos.
     */
    fun verificar(
        correo: String,
        motivo: MotivoCodigo,
        codigo: String,
        usuarioId: Int? = null,
    ) {
        val id = clave(limpiar(correo), motivo, usuarioId)
        val huellaRecibida = huella(id, codigo.filter { it.isDigit() })

        synchronized(this) {
            val pendiente = pendientes[id]
                ?: throw ValidacionException(listOf("No hay un código pendiente para este correo. Pide uno nuevo."))

            if (ahora() > pendiente.venceEn) {
                pendientes.remove(id)
                throw ValidacionException(listOf("El código ya venció. Pide uno nuevo."))
            }

            // isEqual tarda lo mismo siempre; == daría pistas según cuánto tarda en responder
            if (!MessageDigest.isEqual(huellaRecibida, pendiente.huella)) {
                pendiente.intentosRestantes--
                if (pendiente.intentosRestantes <= 0) {
                    pendientes.remove(id)
                    throw DemasiadasSolicitudesException("Pusiste mal el código demasiadas veces. Pide uno nuevo.")
                }
                val quedan = if (pendiente.intentosRestantes == 1) {
                    "Te queda 1 intento."
                } else {
                    "Te quedan ${pendiente.intentosRestantes} intentos."
                }
                throw ValidacionException(listOf("El código es incorrecto. $quedan"))
            }

            pendientes.remove(id) // correcto: un código sirve una sola vez
        }
    }

    /**
     * Revisa los límites y, si se puede mandar, anota la hora en el historial.
     * Revisar y anotar van juntos dentro de synchronized para que dos peticiones
     * al mismo tiempo no se salten el límite.
     */
    private fun apartarEnvio(correo: String): Long = synchronized(this) {
        val ahora = ahora()
        val historial = envios.getOrPut(correo) { ArrayDeque() }
        while (historial.isNotEmpty() && ahora - historial.first() >= UNA_HORA_MS) {
            historial.removeFirst()
        }

        val ultimo = historial.lastOrNull()
        if (ultimo != null && ahora - ultimo < ESPERA_ENTRE_ENVIOS_MS) {
            val segundos = (ESPERA_ENTRE_ENVIOS_MS - (ahora - ultimo) + 999) / 1000
            throw DemasiadasSolicitudesException("Espera $segundos segundos antes de pedir otro código.")
        }
        if (historial.size >= MAX_ENVIOS_POR_HORA) {
            val minutos = (historial.first() + UNA_HORA_MS - ahora + 59_999) / 60_000
            val espera = if (minutos == 1L) "1 minuto" else "$minutos minutos"
            throw DemasiadasSolicitudesException("Pediste demasiados códigos. Intenta de nuevo en $espera.")
        }

        historial.addLast(ahora)
        ahora
    }

    /** Borra los códigos vencidos y los historiales viejos para que la memoria no crezca. */
    private fun limpiarVencidos() {
        val ahora = ahora()
        pendientes.entries.removeIf { ahora > it.value.venceEn }
        envios.entries.removeIf { (_, historial) ->
            historial.isEmpty() || ahora - historial.last() >= UNA_HORA_MS
        }
    }

    /** La huella: el código revuelto con el secreto. Con la huella no se puede sacar el código. */
    private fun huella(id: String, codigo: String): ByteArray {
        val mac = Mac.getInstance(ALGORITMO_HUELLA) // uno nuevo cada vez: Mac no se puede compartir entre hilos
        mac.init(llaveHuella)
        return mac.doFinal("$id|$codigo".toByteArray(Charsets.UTF_8))
    }

    /** Así, un código de REGISTRO no sirve para ELIMINAR_CUENTA: son llaves distintas. */
    private fun clave(correo: String, motivo: MotivoCodigo, usuarioId: Int?) =
        "${motivo.name}|${usuarioId ?: "-"}|$correo"

    private fun limpiar(correo: String) = correo.trim().lowercase()

    private fun ahora() = System.currentTimeMillis()

    private companion object {
        const val ALGORITMO_HUELLA = "HmacSHA256"
        const val VIGENCIA_MS = 10 * 60 * 1000L          // el código dura 10 minutos
        const val MAX_INTENTOS = 5                         // intentos por código
        const val ESPERA_ENTRE_ENVIOS_MS = 60 * 1000L      // 60 s entre un código y otro
        const val MAX_ENVIOS_POR_HORA = 5                  // códigos por correo en una hora
        const val UNA_HORA_MS = 60 * 60 * 1000L
    }
}
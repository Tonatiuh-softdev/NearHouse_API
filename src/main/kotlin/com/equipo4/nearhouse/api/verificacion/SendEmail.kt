package com.equipo4.nearhouse.api.verificacion

import com.equipo4.nearhouse.api.common.ServicioNoDisponibleException
import com.equipo4.nearhouse.api.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Un correo listo para mandar: a quién, el asunto y el contenido (HTML y texto normal). */
data class MensajeCorreo(
    val para: String,
    val asunto: String,
    val html: String,
    val texto: String,
)

/**
 * El "contrato" para mandar correos.
 * Esta parte solo sabe CÓMO mandar (Brevo o consola). QUÉ dice cada correo
 * está en PlantillasCorreo.kt; para agregar un correo nuevo solo se toca ese archivo.
 */
interface EnviadorCorreo {
    suspend fun enviar(mensaje: MensajeCorreo)

    /** Atajo para los códigos: arma el correo según el motivo y lo manda. */
    suspend fun enviarCodigo(
        correo: String,
        codigo: String,
        motivo: MotivoCodigo = MotivoCodigo.REGISTRO,
    ) = enviar(PlantillasCorreo.codigo(correo, codigo, motivo))
}

/**
 * Versión real: manda el correo con la API HTTP de Brevo.
 * Toma de AppConfig: apiBrevo (BREVO_API_KEY), senderEmail (CORREO_REMITENTE)
 * y senderName (NOMBRE_REMITENTE).
 * Se usa la API y no SMTP porque Railway bloquea el SMTP.
 */
class EnviadorCorreoBrevo(private val config: AppConfig) : EnviadorCorreo {

    private val log = LoggerFactory.getLogger(EnviadorCorreoBrevo::class.java)

    // Un solo cliente HTTP que se reutiliza en todos los envíos
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    override suspend fun enviar(mensaje: MensajeCorreo) {
        // 1. Armar el JSON que pide Brevo
        val cuerpo = buildJsonObject {
            putJsonObject("sender") {
                put("name", config.senderName)
                put("email", config.senderEmail)
            }
            putJsonArray("to") {
                addJsonObject { put("email", mensaje.para) }
            }
            put("subject", mensaje.asunto)
            put("htmlContent", mensaje.html)
            put("textContent", mensaje.texto)
        }

        // 2. Preparar la petición POST con los tres encabezados
        val peticion = HttpRequest.newBuilder()
            .uri(URI.create(URL_BREVO))
            .timeout(Duration.ofSeconds(15))
            .header("api-key", config.apiBrevo)
            .header("content-type", "application/json")
            .header("accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(cuerpo.toString()))
            .build()

        // 3. Mandarla en el hilo de entrada/salida para no trabar el servidor
        val respuesta = try {
            withContext(Dispatchers.IO) {
                http.send(peticion, HttpResponse.BodyHandlers.ofString())
            }
        } catch (e: IOException) {
            // Sin internet, Brevo caído o se tardó demasiado
            log.error("No se pudo conectar con Brevo: {}", e.message)
            throw ServicioNoDisponibleException(MENSAJE_ERROR)
        }

        // 4. Revisar la respuesta: Brevo contesta 201 cuando todo sale bien
        if (respuesta.statusCode() !in 200..299) {
            log.error("Brevo respondió {}: {}", respuesta.statusCode(), respuesta.body())
            throw ServicioNoDisponibleException(MENSAJE_ERROR)
        }
        log.info("Correo \"{}\" enviado a {}", mensaje.asunto, mensaje.para)
    }

    private companion object {
        const val URL_BREVO = "https://api.brevo.com/v3/smtp/email"
        const val MENSAJE_ERROR = "No se pudo enviar el correo. Intenta de nuevo en unos minutos"
    }
}

/**
 * Versión de pruebas: no manda nada, solo escribe el correo en la terminal.
 * Sirve para probar en tu computadora sin gastar correos de Brevo.
 */
class EnviadorCorreoConsola : EnviadorCorreo {
    private val log = LoggerFactory.getLogger(EnviadorCorreoConsola::class.java)

    override suspend fun enviar(mensaje: MensajeCorreo) {
        log.warn(
            "[MODO CONSOLA] Para: {} | Asunto: {} | {} (no se mandó ningún correo)",
            mensaje.para, mensaje.asunto, mensaje.texto,
        )
    }
}
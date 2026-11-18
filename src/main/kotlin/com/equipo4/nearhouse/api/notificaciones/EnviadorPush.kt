package com.equipo4.nearhouse.api.notificaciones

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.Notification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.Base64

enum class ResultadoPush { ENVIADO, TOKEN_INVALIDO, ERROR }

interface EnviadorPush {
    suspend fun enviar(token: String, titulo: String, cuerpo: String, datos: Map<String, String>): ResultadoPush
}

/** Sin credenciales: no manda nada, solo deja constancia (sin el texto, que lleva datos del usuario). */
class EnviadorPushConsola : EnviadorPush {
    private val log = LoggerFactory.getLogger(EnviadorPushConsola::class.java)

    override suspend fun enviar(token: String, titulo: String, cuerpo: String, datos: Map<String, String>): ResultadoPush {
        log.info("[PUSH consola] tipo={} (no se envió a Firebase)", datos["tipo"])
        return ResultadoPush.ENVIADO
    }
}

/**
 * Envío real con Firebase Cloud Messaging.
 * [credenciales] es el JSON de la cuenta de servicio, tal cual o en base64.
 */
class EnviadorPushFirebase(credenciales: String, projectId: String) : EnviadorPush {
    private val log = LoggerFactory.getLogger(EnviadorPushFirebase::class.java)
    private val messaging: FirebaseMessaging

    init {
        val json = credenciales.trim().let {
            if (it.startsWith("{")) it else String(Base64.getDecoder().decode(it), Charsets.UTF_8)
        }
        val opciones = FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.fromStream(json.byteInputStream()))
            .setProjectId(projectId)
            .build()
        val app = FirebaseApp.getApps().firstOrNull { it.name == NOMBRE_APP }
            ?: FirebaseApp.initializeApp(opciones, NOMBRE_APP)
        messaging = FirebaseMessaging.getInstance(app)
    }

    override suspend fun enviar(token: String, titulo: String, cuerpo: String, datos: Map<String, String>): ResultadoPush =
        withContext(Dispatchers.IO) {
            try {
                val mensaje = Message.builder()
                    .setToken(token)
                    .setNotification(Notification.builder().setTitle(titulo).setBody(cuerpo).build())
                    .putAllData(datos)
                    .setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH).build())
                    .build()
                messaging.send(mensaje)
                ResultadoPush.ENVIADO
            } catch (e: FirebaseMessagingException) {
                if (e.messagingErrorCode == MessagingErrorCode.UNREGISTERED) {
                    ResultadoPush.TOKEN_INVALIDO
                } else {
                    // errorCode dice si es de credenciales (UNAUTHENTICATED/PERMISSION_DENIED) o del celular
                    log.warn(
                        "Firebase rechazó un envío: messagingErrorCode={} errorCode={} detalle={}",
                        e.messagingErrorCode, e.errorCode, e.message
                    )
                    ResultadoPush.ERROR
                }
            } catch (e: Exception) {
                log.warn("Falló un envío a Firebase: {}", e.message)
                ResultadoPush.ERROR
            }
        }

    private companion object {
        const val NOMBRE_APP = "nearhouse-push"
    }
}
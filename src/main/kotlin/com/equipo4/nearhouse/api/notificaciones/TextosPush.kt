package com.equipo4.nearhouse.api.notificaciones

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Título y texto de cada aviso en español e inglés, con los datos insertados. */
object TextosPush {

    data class Texto(val titulo: String, val cuerpo: String)

    fun armar(tipo: TipoNotificacion, datos: Map<String, String>, idioma: String): Texto {
        val en = idioma == "en"
        val titulo = datos["titulo"].orEmpty()
        val motivo = datos["motivo"].orEmpty()
        val plan = datos["plan"].orEmpty()
        val nombre = datos["nombre"].orEmpty().trim()
        val prueba = datos["prueba"] == "true"
        val vence = fecha(datos["vencimiento"], idioma)
        // Apelaciones: PUBLICACION (anuncio retirado) o SUSPENSION (cuenta suspendida)
        val deCuenta = datos["apelacion"] == "SUSPENSION"

        return when (tipo) {
            TipoNotificacion.BIENVENIDA -> if (en) {
                Texto("Welcome to NearHouse", if (nombre.isEmpty()) "Your account is ready." else "Hi $nombre, your account is ready.")
            } else {
                Texto("Te damos la bienvenida a NearHouse", if (nombre.isEmpty()) "Tu cuenta ya está lista." else "Hola $nombre, tu cuenta ya está lista.")
            }

            TipoNotificacion.PLAN_CONTRATADO -> if (en) {
                Texto(
                    "Thanks for choosing $plan",
                    if (prueba) "Your free trial is active until $vence." else "Your plan is active until $vence."
                )
            } else {
                Texto(
                    "Gracias por elegir $plan",
                    if (prueba) "Tu prueba gratis está activa hasta el $vence." else "Tu plan está activo hasta el $vence."
                )
            }

            TipoNotificacion.PUBLICACION_EN_REVISION -> if (en) {
                Texto("Listing under review", "\"$titulo\" is being reviewed. We'll let you know soon.")
            } else {
                Texto("Anuncio en revisión", "\"$titulo\" está en revisión. Te avisaremos pronto.")
            }

            TipoNotificacion.PUBLICACION_APROBADA -> if (en) {
                Texto("Listing approved", "\"$titulo\" is now live.")
            } else {
                Texto("Anuncio aprobado", "\"$titulo\" ya está publicado.")
            }

            TipoNotificacion.PUBLICACION_RECHAZADA -> if (en) {
                Texto("Listing rejected", "\"$titulo\" was rejected. Reason: $motivo")
            } else {
                Texto("Anuncio rechazado", "\"$titulo\" fue rechazado. Motivo: $motivo")
            }

            // Con motivo: lo desactivó un administrador. Sin motivo: se retiró al atender un reporte
            TipoNotificacion.PUBLICACION_RETIRADA -> when {
                en && motivo.isNotEmpty() -> Texto("Listing removed", "\"$titulo\" is no longer visible. Reason: $motivo")
                en -> Texto("Listing removed", "\"$titulo\" was removed after a report.")
                motivo.isNotEmpty() -> Texto("Anuncio retirado", "\"$titulo\" ya no está visible. Motivo: $motivo")
                else -> Texto("Anuncio retirado", "\"$titulo\" fue retirado a raíz de un reporte.")
            }

            TipoNotificacion.PUBLICACION_ELIMINADA -> when {
                en && motivo.isNotEmpty() -> Texto("Listing deleted", "An administrator deleted \"$titulo\". Reason: $motivo")
                en -> Texto("Listing deleted", "An administrator deleted \"$titulo\".")
                motivo.isNotEmpty() -> Texto("Anuncio eliminado", "Un administrador eliminó \"$titulo\". Motivo: $motivo")
                else -> Texto("Anuncio eliminado", "Un administrador eliminó \"$titulo\".")
            }

            TipoNotificacion.REPORTE_ENVIADO -> if (en) {
                Texto("Report sent", "We received your report about \"$titulo\".")
            } else {
                Texto("Reporte enviado", "Recibimos tu reporte sobre \"$titulo\".")
            }

            TipoNotificacion.REPORTE_ATENDIDO -> if (en) {
                Texto("Your report was handled", "We took action on \"$titulo\". Thanks for helping.")
            } else {
                Texto("Atendimos tu reporte", "Tomamos medidas sobre \"$titulo\". Gracias por avisar.")
            }

            TipoNotificacion.REPORTE_DESCARTADO -> if (en) {
                Texto("Report reviewed", "We reviewed your report about \"$titulo\" and no action was needed.")
            } else {
                Texto("Reporte revisado", "Revisamos tu reporte sobre \"$titulo\" y no procedió.")
            }

            TipoNotificacion.APELACION_ENVIADA -> when {
                en && deCuenta -> Texto("Appeal sent", "An administrator will review your appeal of the suspension.")
                en -> Texto("Appeal sent", "An administrator will review your appeal about \"$titulo\".")
                deCuenta -> Texto("Apelación enviada", "Un administrador revisará tu apelación de la suspensión.")
                else -> Texto("Apelación enviada", "Un administrador revisará tu apelación sobre \"$titulo\".")
            }

            TipoNotificacion.APELACION_ACEPTADA -> when {
                en && deCuenta -> Texto("Appeal accepted", "Your account is active again.")
                en -> Texto("Appeal accepted", "\"$titulo\" is live again.")
                deCuenta -> Texto("Apelación aceptada", "Tu cuenta ya está activa otra vez.")
                else -> Texto("Apelación aceptada", "\"$titulo\" volvió a publicarse.")
            }

            TipoNotificacion.APELACION_RECHAZADA -> when {
                en && deCuenta -> Texto("Appeal reviewed", "The suspension of your account stays. Reason: $motivo")
                en -> Texto("Appeal reviewed", "The decision about \"$titulo\" stays. Reason: $motivo")
                deCuenta -> Texto("Apelación revisada", "La suspensión de tu cuenta se mantiene. Motivo: $motivo")
                else -> Texto("Apelación revisada", "La decisión sobre \"$titulo\" se mantiene. Motivo: $motivo")
            }
        }
    }

    private fun fecha(iso: String?, idioma: String): String {
        if (iso.isNullOrBlank()) return ""
        return try {
            LocalDate.parse(iso).format(
                DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.forLanguageTag(idioma))
            )
        } catch (_: Exception) {
            iso
        }
    }
}
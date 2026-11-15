package com.equipo4.nearhouse.api.verificacion

/**
 * Para qué se pide un código. Cada motivo tiene su propio asunto y texto.
 * Para agregar uno nuevo, solo agrega otro bloque aquí.
 */
enum class MotivoCodigo(
    val asunto: String,
    val titulo: String,
    val accion: String,
    val aviso: String,
) {
    REGISTRO(
        asunto = "Tu código para crear tu cuenta de NearHouse",
        titulo = "Verifica tu correo",
        accion = "terminar de crear tu cuenta",
        aviso = "Si tú no lo pediste, ignora este correo.",
    ),
    CAMBIAR_CORREO(
        asunto = "Tu código para cambiar tu correo en NearHouse",
        titulo = "Confirma tu nuevo correo",
        accion = "confirmar tu nuevo correo",
        aviso = "Si tú no lo pediste, ignora este correo.",
    ),
    RECUPERAR_CONTRASENA(
        asunto = "Tu código para recuperar tu contraseña de NearHouse",
        titulo = "Recupera tu contraseña",
        accion = "crear una contraseña nueva",
        aviso = "Si tú no lo pediste, ignora este correo: tu contraseña sigue igual.",
    ),
    ELIMINAR_CUENTA(
        asunto = "Tu código para eliminar tu cuenta de NearHouse",
        titulo = "Confirma que quieres eliminar tu cuenta",
        accion = "confirmar que quieres eliminar tu cuenta",
        aviso = "Eliminar tu cuenta no se puede deshacer. " +
            "Si tú no lo pediste, alguien entró a tu cuenta: cambia tu contraseña cuanto antes.",
    ),
    CAMBIAR_CONTRASENA(
        asunto = "Tu código para cambiar tu contraseña de NearHouse",
        titulo = "Confirma el cambio de contraseña",
        accion = "confirmar el cambio de tu contraseña",
        aviso = "Si tú no lo pediste, ignora este correo: tu contraseña sigue igual.",
    ),
    CREAR_CONTRASENA(
        asunto = "Tu código para crear tu contraseña de NearHouse",
        titulo = "Crea tu contraseña",
        accion = "crear la contraseña de tu cuenta",
        aviso = "Si tú no lo pediste, ignora este correo: tu cuenta sigue igual.",
    ),
}

/**
 * QUÉ dice cada correo. Cada función regresa un MensajeCorreo listo para mandar.
 * Para agregar un correo nuevo (por ejemplo, "tu anuncio fue aprobado"),
 * agrega aquí otra función que regrese un MensajeCorreo.
 */
object PlantillasCorreo {

    /** Correo con un código de 6 dígitos; el texto cambia según el motivo. */
    fun codigo(para: String, codigo: String, motivo: MotivoCodigo) = MensajeCorreo(
        para = para,
        asunto = "${motivo.asunto}: $codigo",
        html = diseno(
            motivo.titulo,
            "<p>Usa este código para ${motivo.accion}:</p>" +
                "<p style=\"font-size: 32px; font-weight: bold; letter-spacing: 6px;\">$codigo</p>" +
                "<p>No compartas este código con nadie. ${motivo.aviso}</p>",
        ),
        texto = "Tu código de NearHouse para ${motivo.accion} es $codigo. " +
            "No lo compartas con nadie. ${motivo.aviso}",
    )

    /** Correo de aviso cuando se desactiva la cuenta. */
    fun cuentaDesactivada(para: String) = MensajeCorreo(
        para = para,
        asunto = "Desactivaste tu cuenta de NearHouse",
        html = diseno(
            "Desactivaste tu cuenta",
            "<p>Desactivaste tu cuenta de NearHouse. Tus anuncios ya no se ven.</p>" +
                "<p>Para volver, solo inicia sesión en la app.</p>",
        ),
        texto = "Desactivaste tu cuenta de NearHouse. Tus anuncios ya no se ven. Para volver, solo inicia sesión en la app.",
    )

    /** Correo de aviso cuando el usuario pide eliminar su cuenta: se borra en 30 días si no inicia sesión. */
    fun eliminacionProgramada(para: String, fecha: String) = MensajeCorreo(
        para = para,
        asunto = "Tu cuenta de NearHouse se eliminará el $fecha",
        html = diseno(
            "Tu cuenta se eliminará",
            "<p>Recibimos tu solicitud para eliminar tu cuenta de NearHouse. Tus anuncios ya no se ven.</p>" +
                "<p>Tu cuenta y todos tus datos se borrarán el <b>$fecha</b>. Después de esa fecha ya no se podrán recuperar.</p>" +
                "<p>Si cambias de opinión, inicia sesión en la app antes de esa fecha y elige conservar tu cuenta.</p>",
        ),
        texto = "Recibimos tu solicitud para eliminar tu cuenta de NearHouse. Tu cuenta y todos tus datos se borrarán el $fecha. " +
            "Si cambias de opinión, inicia sesión en la app antes de esa fecha y elige conservar tu cuenta.",
    )

    /** Correo de aviso cuando se cambia la contraseña. */
    fun contrasenaCambiada(para: String, fechaHora: String) = MensajeCorreo(
        para = para,
        asunto = "Tu contraseña de NearHouse se cambió",
        html = diseno(
            "Tu contraseña se cambió",
            "<p>Tu contraseña de NearHouse se cambió el $fechaHora.</p>" +
                "<p>Si no fuiste tú, recupera tu cuenta con ‘¿Olvidaste tu contraseña?’.</p>",
        ),
        texto = "Tu contraseña de NearHouse se cambió el $fechaHora. Si no fuiste tú, recupera tu cuenta con ‘¿Olvidaste tu contraseña?’.",
    )

    /** Correo de aviso cuando se crea una contraseña (cuenta de Google). */
    fun contrasenaCreada(para: String, fechaHora: String) = MensajeCorreo(
        para = para,
        asunto = "Creaste una contraseña en NearHouse",
        html = diseno(
            "Creaste una contraseña",
            "<p>El $fechaHora se creó una contraseña para tu cuenta de NearHouse.</p>" +
                "<p>Ahora puedes entrar con tu correo y contraseña, o con Google. Si no fuiste tú, recupera tu cuenta con ‘¿Olvidaste tu contraseña?’.</p>",
        ),
        texto = "El $fechaHora se creó una contraseña para tu cuenta de NearHouse. Ahora puedes entrar con tu correo y contraseña, o con Google. Si no fuiste tú, recupera tu cuenta con ‘¿Olvidaste tu contraseña?’.",
    )

    /** Correo de aviso cuando se cambia el correo de la cuenta. */
    fun correoCambiado(para: String, correoNuevoOculto: String, fechaHora: String) = MensajeCorreo(
        para = para,
        asunto = "El correo de tu cuenta de NearHouse se cambió",
        html = diseno(
            "El correo de tu cuenta se cambió",
            "<p>El $fechaHora el correo de tu cuenta de NearHouse se cambió a $correoNuevoOculto.</p>" +
                "<p>Si no fuiste tú, escríbenos a soporte@nearhouse.com lo antes posible.</p>",
        ),
        texto = "El $fechaHora el correo de tu cuenta de NearHouse se cambió a $correoNuevoOculto. Si no fuiste tú, escríbenos a soporte@nearhouse.com lo antes posible.",
    )

    /** Correo de aviso cuando se cambia el teléfono de la cuenta. */
    fun telefonoCambiado(para: String, telefonoNuevoOculto: String) = MensajeCorreo(
        para = para,
        asunto = "El teléfono de tu cuenta NearHouse se cambió",
        html = diseno(
            "El teléfono de tu cuenta se cambió",
            "<p>El teléfono de tu cuenta NearHouse se cambió a $telefonoNuevoOculto.</p>" +
                "<p>Si no fuiste tú, contáctanos lo antes posible.</p>",
        ),
        texto = "El teléfono de tu cuenta NearHouse se cambió a $telefonoNuevoOculto. Si no fuiste tú, contáctanos.",
    )

    /** Ejemplo de correo sin código: aviso de que la cuenta ya se eliminó. */
    fun cuentaEliminada(para: String) = MensajeCorreo(
        para = para,
        asunto = "Tu cuenta de NearHouse fue eliminada",
        html = diseno(
            "Tu cuenta fue eliminada",
            "<p>Tu cuenta de NearHouse y tus anuncios se eliminaron.</p>" +
                "<p>Gracias por usar NearHouse. Si quieres volver, puedes crear una cuenta nueva cuando quieras.</p>",
        ),
        texto = "Tu cuenta de NearHouse y tus anuncios se eliminaron. Gracias por usar NearHouse.",
    )

    /** Correo de aviso cuando el usuario cancela su suscripción. */
    fun planCancelado(para: String, planNombre: String) = MensajeCorreo(
        para = para,
        asunto = "Tu suscripción de NearHouse fue cancelada",
        html = diseno(
            "Tu suscripción se canceló",
            "<p>Cancelaste tu plan <b>$planNombre</b>. Tu cuenta vuelve a ser de comprador y tus anuncios ya no se ven.</p>" +
                "<p>Puedes contratar un plan nuevo cuando quieras y tus anuncios volverán a mostrarse.</p>",
        ),
        texto = "Cancelaste tu plan $planNombre. Tu cuenta vuelve a ser de comprador y tus anuncios ya no se ven. Puedes contratar un plan nuevo cuando quieras y tus anuncios volverán a mostrarse.",
    )

    /**
     * Resultado de la apelación de una suspensión. La cuenta suspendida no puede entrar a ver sus avisos,
     * por eso también se le avisa por correo. [nota] es la respuesta del administrador.
     */
    fun apelacionResuelta(para: String, aceptada: Boolean, nota: String): MensajeCorreo {
        val notaHtml = escaparHtml(nota)
        return if (aceptada) {
            MensajeCorreo(
                para = para,
                asunto = "Revisamos tu apelación: tu cuenta de NearHouse está activa",
                html = diseno(
                    "Tu cuenta está activa otra vez",
                    "<p>Revisamos tu apelación y quitamos la suspensión de tu cuenta. Ya puedes iniciar sesión en NearHouse.</p>" +
                        "<p><b>Respuesta del administrador:</b> $notaHtml</p>",
                ),
                texto = "Revisamos tu apelación y quitamos la suspensión de tu cuenta. Ya puedes iniciar sesión en NearHouse. " +
                    "Respuesta del administrador: $nota",
            )
        } else {
            MensajeCorreo(
                para = para,
                asunto = "Revisamos tu apelación de NearHouse",
                html = diseno(
                    "Revisamos tu apelación",
                    "<p>Revisamos tu apelación y la suspensión de tu cuenta se mantiene.</p>" +
                        "<p><b>Respuesta del administrador:</b> $notaHtml</p>",
                ),
                texto = "Revisamos tu apelación y la suspensión de tu cuenta se mantiene. Respuesta del administrador: $nota",
            )
        }
    }

    /** Para meter texto escrito por una persona dentro del HTML del correo sin que se interprete como etiquetas. */
    private fun escaparHtml(texto: String) = texto
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** El marco que comparten todos los correos (letra, ancho y título). */
    private fun diseno(titulo: String, contenido: String) =
        "<div style=\"font-family: Arial, sans-serif; max-width: 480px; margin: auto; color: #222;\">" +
            "<h2>$titulo</h2>" +
            contenido +
            "</div>"
}

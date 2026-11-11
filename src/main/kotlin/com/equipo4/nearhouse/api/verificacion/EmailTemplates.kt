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

    /** El marco que comparten todos los correos (letra, ancho y título). */
    private fun diseno(titulo: String, contenido: String) =
        "<div style=\"font-family: Arial, sans-serif; max-width: 480px; margin: auto; color: #222;\">" +
            "<h2>$titulo</h2>" +
            contenido +
            "</div>"
}

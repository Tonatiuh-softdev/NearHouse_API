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
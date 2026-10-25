package com.equipo4.nearhouse.api.auth

import org.mindrot.jbcrypt.BCrypt

/**
 * Las contraseñas nunca se guardan tal cual: se guarda su hash bcrypt.
 * Es el mismo formato ($2a$) que usamos para el super administrador del script SQL.
 */
object PasswordHasher {

    private const val COSTO = 12

    fun hash(contrasena: String): String = BCrypt.hashpw(contrasena, BCrypt.gensalt(COSTO))

    fun verificar(contrasena: String, hash: String): Boolean =
        try {
            BCrypt.checkpw(contrasena, hash)
        } catch (e: IllegalArgumentException) {
            false // el hash guardado no tiene formato bcrypt
        }
}

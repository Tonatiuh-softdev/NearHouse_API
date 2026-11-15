package com.equipo4.nearhouse.api.auth

/** Reglas de contraseña. Deben coincidir con com.equipo4.nearhome.util.PasswordValidator de la app. */
enum class ReglaContrasena {
    LONGITUD_MINIMA,
    MAYUSCULA,
    MINUSCULA,
    NUMERO,
    CARACTER_ESPECIAL,
    SIN_SECUENCIAS
}

object PoliticaContrasena {

    const val CARACTERES_ESPECIALES = "!@#$%&*?._-"
    private const val LONGITUD_MINIMA = 8

    fun cumple(regla: ReglaContrasena, c: String): Boolean = when (regla) {
        ReglaContrasena.LONGITUD_MINIMA -> c.length >= LONGITUD_MINIMA
        ReglaContrasena.MAYUSCULA -> c.any { it.isUpperCase() }
        ReglaContrasena.MINUSCULA -> c.any { it.isLowerCase() }
        ReglaContrasena.NUMERO -> c.any { it.isDigit() }
        ReglaContrasena.CARACTER_ESPECIAL -> c.any { it in CARACTERES_ESPECIALES }
        ReglaContrasena.SIN_SECUENCIAS -> !tieneSecuenciaORepetido(c.lowercase())
    }

    /** Lista vacía = contraseña válida. */
    fun validar(contrasena: String): List<String> =
        ReglaContrasena.entries.filterNot { cumple(it, contrasena) }.map { mensaje(it) }

    fun mensaje(regla: ReglaContrasena): String = when (regla) {
        ReglaContrasena.LONGITUD_MINIMA -> "La contraseña debe tener al menos 8 caracteres"
        ReglaContrasena.MAYUSCULA -> "La contraseña debe tener al menos una letra mayúscula"
        ReglaContrasena.MINUSCULA -> "La contraseña debe tener al menos una letra minúscula"
        ReglaContrasena.NUMERO -> "La contraseña debe tener al menos un número"
        ReglaContrasena.CARACTER_ESPECIAL -> "La contraseña debe tener al menos un carácter especial (! @ # $ % & * ? . _ -)"
        ReglaContrasena.SIN_SECUENCIAS -> "La contraseña no puede tener 3 caracteres seguidos en orden (abc, 123) ni el mismo repetido 3 veces (aaa, 111)"
    }

    private fun tieneSecuenciaORepetido(c: String): Boolean {
        for (i in 0..c.length - 3) {
            val a = c[i]; val b = c[i + 1]; val d = c[i + 2]
            if (a == b && b == d) return true
            val paso1 = b.code - a.code
            val paso2 = d.code - b.code
            if (paso1 == paso2 && (paso1 == 1 || paso1 == -1)) return true
        }
        return false
    }
}
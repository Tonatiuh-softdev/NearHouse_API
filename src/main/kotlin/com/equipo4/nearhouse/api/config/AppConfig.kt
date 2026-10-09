package com.equipo4.nearhouse.api.config

/**
 * Toda la configuración sale de variables de entorno, así el mismo código
 * funciona en tu laptop, en Docker y en Railway sin cambios.
 */
data class AppConfig(
    val port: Int,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val jwtSecret: String,
    val jwtIssuer: String = "nearhouse-api",
    val jwtAudience: String = "nearhouse-app",
    val jwtExpiracionHoras: Long = 24 * 7, // el token dura 7 días
) {
    companion object {
        fun fromEnv(): AppConfig {
            val jwtSecret = requerida("JWT_SECRET")
            require(jwtSecret.length >= 32) {
                "JWT_SECRET debe tener al menos 32 caracteres para que los tokens sean seguros."
            }
            return AppConfig(
                port = System.getenv("PORT")?.toIntOrNull() ?: 8080,
                dbUrl = requerida("DB_URL"),
                dbUser = requerida("DB_USER"),
                dbPassword = requerida("DB_PASSWORD"),
                jwtSecret = jwtSecret,
            )
        }

        private fun requerida(nombre: String): String =
            System.getenv(nombre)?.takeIf { it.isNotBlank() }
                ?: error("Falta la variable de entorno $nombre. Revisa tu archivo .env o las variables de Railway.")
    }
}

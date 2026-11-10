package com.equipo4.nearhouse.api.config

/**
 * Toda la configuración sale de variables de entorno (Railway o el archivo .env local).
 * Las obligatorias no tienen valor por defecto: si faltan, la API no arranca y dice cuál.
 */
data class AppConfig(
    val port: Int,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val jwtSecret: String,
    val firebaseProjectId: String = "nearhouse-70367",
    /** JSON de la cuenta de servicio de Firebase. Vacío = las notificaciones van a consola. */
    val firebaseCredenciales: String = "",
    val jwtIssuer: String = "nearhouse-api",
    val jwtAudience: String = "nearhouse-app",
    val jwtExpiracionHoras: Long = 24 * 7, // el token dura 7 días

    val apiBrevo: String,
    val senderEmail: String,
    val senderName: String,
    val correoModoConsola: Boolean = false
) {
    companion object {
        fun fromEnv(): AppConfig {
            val jwtSecret = requerida("JWT_SECRET")
            require(jwtSecret.length >= 32) { "JWT_SECRET debe tener al menos 32 caracteres" }

            return AppConfig(
                port = System.getenv("PORT")?.toIntOrNull() ?: 8080,
                dbUrl = requerida("DB_URL"),
                dbUser = requerida("DB_USER"),
                dbPassword = requerida("DB_PASSWORD"),
                jwtSecret = jwtSecret,
                firebaseProjectId = opcional("FIREBASE_PROJECT_ID").ifBlank { "nearhouse-70367" },
                firebaseCredenciales = opcional("FIREBASE_CREDENCIALES"),
                apiBrevo = opcional("BREVO_API_KEY"),
                senderEmail = opcional("CORREO_REMITENTE"),
                senderName = opcional("NOMBRE_REMITENTE").ifBlank { "NearHouse" },
                correoModoConsola = opcional("CORREO_MODO_CONSOLA").equals("true", ignoreCase = true),
            )
        }

        /** Variable obligatoria: si falta o está vacía, la API no arranca y dice cuál es. */
        private fun requerida(nombre: String): String =
            System.getenv(nombre)?.trim()?.takeIf { it.isNotEmpty() }
                ?: error("Falta la variable de entorno $nombre")

        /** Variable opcional: si no existe, regresa texto vacío. */
        private fun opcional(nombre: String): String =
            System.getenv(nombre)?.trim() ?: ""
    }
}
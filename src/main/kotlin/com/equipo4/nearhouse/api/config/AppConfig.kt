package com.equipo4.nearhouse.api.config

/**
 * Toda la configuración sale de variables de entorno (en Railway/Docker).
 * Si no se detectan en local, utiliza la base de datos de Railway por defecto.
 */
data class AppConfig(
    val port: Int,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val jwtSecret: String,
    val firebaseProjectId: String = "nearhouse-70367",
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
            return AppConfig(
                port = System.getenv("PORT")?.toIntOrNull() ?: 8080,
                dbUrl = System.getenv("DB_URL") 
                    ?: "jdbc:postgresql://tokaido.proxy.rlwy.net:20860/railway",
                dbUser = System.getenv("DB_USER") ?: "postgres",
                dbPassword = System.getenv("DB_PASSWORD") ?: "VKzTcRKmAsJJKuVVBpMtwhucSZAWTnLH",
                jwtSecret = System.getenv("JWT_SECRET") 
                    ?: "FrQiKNtqFDsJLCiZWw6dUBO9GVaHCxm5luVO1R72gSjTkM8b",
                firebaseProjectId = System.getenv("FIREBASE_PROJECT_ID") ?: "nearhouse-70367",
                apiBrevo = opcional("BREVO_API_KEY"),
                senderEmail = opcional("CORREO_REMITENTE"),
                senderName = opcional("NOMBRE_REMITENTE").ifBlank { "NearHouse" },
                correoModoConsola = opcional("CORREO_MODO_CONSOLA").equals("true", ignoreCase = true),
            )
        }
 
        /** Lee una variable de entorno; si no existe, regresa texto vacío en lugar de detener la API. */
        private fun opcional(nombre: String): String =
            System.getenv(nombre)?.trim() ?: ""
    }
}
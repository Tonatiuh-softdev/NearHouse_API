package com.equipo4.nearhouse.api.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

/**
 * Crea un "pool" de conexiones: en lugar de abrir una conexión nueva a PostgreSQL
 * en cada petición (lento), mantiene unas cuantas abiertas y las reutiliza.
 */
object DatabaseFactory {

    fun createDataSource(config: AppConfig): HikariDataSource {
        val hikari = HikariConfig().apply {
            jdbcUrl = config.dbUrl
            username = config.dbUser
            password = config.dbPassword
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 5          // suficiente para el proyecto y amable con Railway
            connectionTimeout = 10_000   // 10 s esperando una conexión libre
            poolName = "nearhouse-pool"
        }
        // Si los datos de conexión están mal, la API falla al arrancar con un mensaje claro.
        return HikariDataSource(hikari)
    }
}

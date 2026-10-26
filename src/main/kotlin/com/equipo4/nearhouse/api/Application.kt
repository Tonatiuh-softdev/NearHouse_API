package com.equipo4.nearhouse.api

import com.equipo4.nearhouse.api.auth.FirebaseTokenVerifier
import com.equipo4.nearhouse.api.auth.GoogleProofService
import com.equipo4.nearhouse.api.auth.JwtService
import com.equipo4.nearhouse.api.colonias.ColoniaRepository
import com.equipo4.nearhouse.api.colonias.ColoniaService
import com.equipo4.nearhouse.api.config.AppConfig
import com.equipo4.nearhouse.api.config.DatabaseFactory
import com.equipo4.nearhouse.api.plugins.configureMonitoring
import com.equipo4.nearhouse.api.plugins.configureRouting
import com.equipo4.nearhouse.api.plugins.configureSecurity
import com.equipo4.nearhouse.api.plugins.configureSerialization
import com.equipo4.nearhouse.api.plugins.configureStatusPages
import com.equipo4.nearhouse.api.publicaciones.PublicacionRepository
import com.equipo4.nearhouse.api.publicaciones.PublicacionService
import com.equipo4.nearhouse.api.usuarios.UsuarioRepository
import com.equipo4.nearhouse.api.usuarios.UsuarioService
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * Punto de entrada de la API. Lee la configuración de las variables de entorno
 * y levanta el servidor en 0.0.0.0 (necesario para Docker y Railway).
 */
fun main() {
    val config = AppConfig.fromEnv()
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(config)
    }.start(wait = true)
}

/**
 * Arma la aplicación: crea las dependencias (base de datos, servicios)
 * e instala los plugins de Ktor en orden.
 */
fun Application.module(config: AppConfig) {
    // Conexión a PostgreSQL (se cierra al apagar el servidor)
    val dataSource = DatabaseFactory.createDataSource(config)
    monitor.subscribe(ApplicationStopped) { dataSource.close() }

    // Servicios
    val jwtService = JwtService(config)
    
    // Instancias para la verificación de Google y generación de comprobantes
    val firebaseTokenVerifier = FirebaseTokenVerifier(
        projectId = System.getenv("FIREBASE_PROJECT_ID") ?: ""
    )
    val googleProofService = GoogleProofService(
        jwtSecret = System.getenv("JWT_SECRET") ?: "FrQiKNtqFDsJLCiZWw6dUBO9GVaHCxm5luVO1R72gSjTkM8b"
    )

    val usuarioRepository = UsuarioRepository(dataSource)
    
    // Se pasan los 4 parámetros requeridos por UsuarioService
    val usuarioService = UsuarioService(
        repositorio = usuarioRepository,
        jwtService = jwtService,
        firebaseTokenVerifier = firebaseTokenVerifier,
        googleProofService = googleProofService
    )

    // Catálogo de colonias para el filtro de Ubicación
    val coloniaService = ColoniaService(ColoniaRepository(dataSource))

    // Crear anuncios (con fotos)
    val publicacionService = PublicacionService(PublicacionRepository(dataSource))

    // Plugins
    configureSerialization()
    configureMonitoring()
    configureStatusPages()
    configureSecurity(jwtService)
    configureRouting(dataSource, usuarioService, coloniaService, publicacionService)
}
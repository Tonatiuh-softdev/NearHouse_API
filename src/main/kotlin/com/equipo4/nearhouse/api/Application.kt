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
import com.equipo4.nearhouse.api.reportes.ReporteRepository
import com.equipo4.nearhouse.api.reportes.ReporteService
import com.equipo4.nearhouse.api.usuarios.UsuarioRepository
import com.equipo4.nearhouse.api.usuarios.UsuarioService
import com.equipo4.nearhouse.api.verificacion.CodigosCorreo
import com.equipo4.nearhouse.api.verificacion.EnviadorCorreo
import com.equipo4.nearhouse.api.verificacion.EnviadorCorreoBrevo
import com.equipo4.nearhouse.api.verificacion.EnviadorCorreoConsola
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

    // Verificación de Google y comprobantes. Todo sale de config, no de System.getenv
    val firebaseTokenVerifier = FirebaseTokenVerifier(projectId = config.firebaseProjectId)
    val googleProofService = GoogleProofService(jwtSecret = config.jwtSecret)

    // Correo: Consola si está activo CORREO_MODO_CONSOLA=true; Brevo si hay llave y remitente válidos.
    val enviadorCorreo: EnviadorCorreo = when {
        config.correoModoConsola -> EnviadorCorreoConsola()
        config.apiBrevo.isNotBlank() && config.senderEmail.isNotBlank() -> EnviadorCorreoBrevo(config)
        else -> EnviadorCorreoConsola()
    }

    // UNO solo para toda la API: aquí viven los códigos pendientes
    val codigosCorreo = CodigosCorreo(config.jwtSecret)

    val usuarioRepository = UsuarioRepository(dataSource)

    val usuarioService = UsuarioService(
        repositorio = usuarioRepository,
        jwtService = jwtService,
        firebaseTokenVerifier = firebaseTokenVerifier,
        googleProofService = googleProofService,
        codigos = codigosCorreo,
        enviador = enviadorCorreo,
    )

    // Catálogo de colonias para el filtro de Ubicación
    val coloniaService = ColoniaService(ColoniaRepository(dataSource))

    // Crear anuncios (con fotos)
    val publicacionService = PublicacionService(PublicacionRepository(dataSource))

    // Reportes de publicaciones
    val reporteService = ReporteService(ReporteRepository(dataSource))

    // Plugins
    configureSerialization()
    configureMonitoring()
    configureStatusPages()
    configureSecurity(jwtService, usuarioRepository)
    configureRouting(dataSource, usuarioService, coloniaService, publicacionService, reporteService)
}

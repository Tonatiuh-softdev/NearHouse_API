package com.equipo4.nearhouse.api

import com.equipo4.nearhouse.api.admin.usuarios.AdminUsuarioRepository
import com.equipo4.nearhouse.api.apelaciones.ApelacionRepository
import com.equipo4.nearhouse.api.apelaciones.ApelacionService
import com.equipo4.nearhouse.api.apelaciones.ApelacionTokens
import com.equipo4.nearhouse.api.admin.usuarios.AdminUsuarioService
import com.equipo4.nearhouse.api.auth.FirebaseTokenVerifier
import com.equipo4.nearhouse.api.auth.GoogleProofService
import com.equipo4.nearhouse.api.auth.JwtService
import com.equipo4.nearhouse.api.colonias.ColoniaRepository
import com.equipo4.nearhouse.api.colonias.ColoniaService
import com.equipo4.nearhouse.api.config.AppConfig
import com.equipo4.nearhouse.api.config.DatabaseFactory
import com.equipo4.nearhouse.api.guardados.GuardadoRepository
import com.equipo4.nearhouse.api.guardados.GuardadoService
import com.equipo4.nearhouse.api.notificaciones.DispositivoRepository
import com.equipo4.nearhouse.api.notificaciones.DispositivoService
import com.equipo4.nearhouse.api.notificaciones.EnviadorPush
import com.equipo4.nearhouse.api.notificaciones.EnviadorPushConsola
import com.equipo4.nearhouse.api.notificaciones.EnviadorPushFirebase
import com.equipo4.nearhouse.api.notificaciones.NotificacionRepository
import com.equipo4.nearhouse.api.notificaciones.NotificacionService
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
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

    // Servicios base
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

    // Push: Firebase real si hay FIREBASE_CREDENCIALES; si no, solo consola
    val enviadorPush: EnviadorPush =
        if (config.firebaseCredenciales.isNotBlank()) {
            EnviadorPushFirebase(config.firebaseCredenciales, config.firebaseProjectId)
        } else {
            EnviadorPushConsola()
        }

    // Notificaciones y dispositivos: se crean antes de los servicios que las usan
    val dispositivoRepository = DispositivoRepository(dataSource)
    val dispositivoService = DispositivoService(dispositivoRepository)
    val notificacionService = NotificacionService(
        NotificacionRepository(dataSource),
        dispositivoRepository,
        enviadorPush,
    )

    val usuarioRepository = UsuarioRepository(dataSource)

    // Apelaciones: anuncios retirados por un reporte y cuentas suspendidas
    val apelacionService = ApelacionService(
        ApelacionRepository(dataSource),
        notificacionService,
        enviadorCorreo,
        ApelacionTokens(config.jwtSecret),
    )

    val usuarioService = UsuarioService(
        repositorio = usuarioRepository,
        jwtService = jwtService,
        firebaseTokenVerifier = firebaseTokenVerifier,
        googleProofService = googleProofService,
        codigos = codigosCorreo,
        enviador = enviadorCorreo,
        notificaciones = notificacionService,
        apelaciones = apelacionService,
    )

    // Limpieza diaria: notificaciones antiguas.
    // Se revisa al arrancar y luego cada 24 horas. Si la API se apaga, se detiene sola.
    launch {
        while (isActive) {
            try {
                val notifBorradas = notificacionService.limpiar()
                if (notifBorradas > 0) log.info("Notificaciones antiguas borradas: {}", notifBorradas)
            } catch (e: Exception) {
                log.error("Falló la limpieza diaria: {}", e.message)
            }
            delay(24 * 60 * 60 * 1000L)
        }
    }

    // Borrado de cuentas vencidas: revisa al arrancar y luego cada 24 horas
    launch {
        while (isActive) {
            try {
                val borradas = usuarioService.borrarCuentasVencidas()
                if (borradas > 0) log.info("Cuentas eliminadas definitivamente: {}", borradas)
            } catch (e: Exception) {
                log.error("Falló el borrado de cuentas vencidas: {}", e.message)
            }
            delay(24 * 60 * 60 * 1000L)
        }
    }

    // Catálogo de colonias para el filtro de Ubicación
    val coloniaService = ColoniaService(ColoniaRepository(dataSource))

    // Crear anuncios (con fotos)
    val publicacionRepository = PublicacionRepository(dataSource)
    val publicacionService = PublicacionService(publicacionRepository, notificacionService, apelacionService)

    // Reportes de publicaciones
    val reporteService = ReporteService(ReporteRepository(dataSource), notificacionService)

    // Panel de administración: cuentas de usuarios
    val adminUsuarioService = AdminUsuarioService(AdminUsuarioRepository(dataSource))

    // Guardados de publicaciones
    val guardadoRepository = GuardadoRepository(dataSource, publicacionRepository)
    val guardadoService = GuardadoService(guardadoRepository)

    // Plugins
    configureSerialization()
    configureMonitoring()
    configureStatusPages()
    configureSecurity(jwtService, usuarioRepository)
    configureRouting(
        dataSource,
        usuarioService,
        coloniaService,
        publicacionService,
        reporteService,
        adminUsuarioService,
        notificacionService,
        dispositivoService,
        guardadoService,
        apelacionService,
    )
}
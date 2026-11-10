package com.equipo4.nearhouse.api.notificaciones

import com.equipo4.nearhouse.api.common.ValidacionException

class DispositivoService(private val repositorio: DispositivoRepository) {

    suspend fun registrar(usuarioId: Int, req: RegistrarDispositivoRequest) {
        val token = req.token.trim()
        if (token.length !in 20..4096) throw ValidacionException(listOf("El token del dispositivo no es válido"))
        // "es-MX" -> "es"; cualquier otro idioma se manda en español
        val idioma = req.idioma.trim().lowercase().take(2).let { if (it == "en") "en" else "es" }
        repositorio.registrar(usuarioId, token, idioma)
    }

    suspend fun baja(usuarioId: Int, req: BajaDispositivoRequest) {
        repositorio.baja(usuarioId, req.token.trim())
    }
}
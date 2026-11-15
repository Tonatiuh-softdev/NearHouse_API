package com.equipo4.nearhouse.api.publicaciones

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.net.URLEncoder
import java.text.NumberFormat
import java.util.Locale

/**
 * Página pública de un anuncio para "Compartir" (GET /p/{id}).
 * - Trae las etiquetas Open Graph: WhatsApp, Telegram, etc. muestran la foto, el título y el precio.
 * - El botón abre el anuncio en la app (nearhouse://publication_detail/{id}) si está instalada.
 * Solo muestra anuncios visibles (publicados, activos y de una cuenta activa con plan) y nunca
 * los datos de contacto del vendedor.
 */
fun Route.compartirRoutes(service: PublicacionService) {
    get("/p/{id}") {
        val ingles = call.request.headers["Accept-Language"].orEmpty().lowercase().startsWith("en")
        val id = call.parameters["id"]?.toIntOrNull()
        val anuncio = id?.let { service.paraCompartir(it) }
        if (anuncio == null) {
            call.respondText(paginaNoDisponible(ingles), ContentType.Text.Html, HttpStatusCode.NotFound)
        } else {
            call.respondText(paginaAnuncio(anuncio, call.baseUrl(), ingles), ContentType.Text.Html)
        }
    }
}

/** "https://dominio" tal como lo vio quien abrió el enlace (Railway pone la API detrás de un proxy). */
private fun ApplicationCall.baseUrl(): String {
    val esquema = request.headers["X-Forwarded-Proto"]?.substringBefore(',')?.trim() ?: "https"
    val host = request.headers["X-Forwarded-Host"] ?: request.headers["Host"] ?: "localhost"
    return "$esquema://$host"
}

private fun paginaAnuncio(a: PublicacionResponse, base: String, ingles: Boolean): String {
    val precio = NumberFormat.getNumberInstance(Locale.forLanguageTag("es-MX"))
        .apply { maximumFractionDigits = 0 }
        .format(a.precio)
    val esRenta = a.modalidad.equals("Renta", ignoreCase = true)
    val modalidad = when {
        ingles && esRenta -> "For rent"
        ingles -> "For sale"
        esRenta -> "En renta"
        else -> "En venta"
    }
    val tipo = when (a.tipo) {
        "CASA" -> if (ingles) "House" else "Casa"
        "DEPARTAMENTO" -> if (ingles) "Apartment" else "Departamento"
        else -> if (ingles) "Land" else "Terreno"
    }
    val precioTexto = "MX $$precio" + if (esRenta) (if (ingles) " / month" else " / mes") else ""
    val lugar = listOf(a.colonia, "Manzanillo, Colima").filter { it.isNotBlank() }.joinToString(", ")
    val resumen = "$tipo · $modalidad · $precioTexto"
    val foto = a.fotos.firstOrNull()?.let { "$base/api/v1/fotos/$it" }
    val urlPagina = "$base/p/${a.id}"
    val fallback = URLEncoder.encode("$urlPagina?app=0", "UTF-8")
    val abrirApp = "intent://publication_detail/${a.id}#Intent;scheme=nearhouse;package=com.equipo4.nearhome;" +
        "S.browser_fallback_url=$fallback;end"

    val t = Textos(ingles)
    val ogImagen = foto?.let {
        """<meta property="og:image" content="${html(it)}">
  <meta name="twitter:card" content="summary_large_image">"""
    }.orEmpty()
    val imagen = foto?.let { """<img class="foto" src="${html(it)}" alt="">""" }.orEmpty()

    return """<!doctype html>
<html lang="${if (ingles) "en" else "es"}">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${html(a.titulo)} · NearHouse</title>
  <meta name="description" content="${html("$resumen · $lugar")}">
  <meta property="og:type" content="website">
  <meta property="og:site_name" content="NearHouse">
  <meta property="og:title" content="${html(a.titulo)}">
  <meta property="og:description" content="${html("$resumen · $lugar")}">
  <meta property="og:url" content="${html(urlPagina)}">
  $ogImagen
  ${estilos()}
</head>
<body>
  <main class="tarjeta">
    $imagen
    <div class="cuerpo">
      <p class="etiqueta">${html("$tipo · $modalidad")}</p>
      <h1>${html(a.titulo)}</h1>
      <p class="precio">${html(precioTexto)}</p>
      <p class="lugar">${html(lugar)}</p>
      <a class="boton" href="${html(abrirApp)}">${t.abrir}</a>
      <p id="sin-app" class="nota">${t.sinApp}</p>
    </div>
  </main>
  <p class="marca">NearHouse</p>
  <script>
    if (new URLSearchParams(location.search).get('app') === '0') {
      document.getElementById('sin-app').classList.add('visible');
    }
  </script>
</body>
</html>"""
}

private fun paginaNoDisponible(ingles: Boolean): String {
    val t = Textos(ingles)
    return """<!doctype html>
<html lang="${if (ingles) "en" else "es"}">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>NearHouse</title>
  <meta property="og:title" content="NearHouse">
  <meta property="og:description" content="${t.noDisponible}">
  ${estilos()}
</head>
<body>
  <main class="tarjeta"><div class="cuerpo">
    <h1>${t.noDisponible}</h1>
    <p class="lugar">${t.noDisponibleDetalle}</p>
  </div></main>
  <p class="marca">NearHouse</p>
</body>
</html>"""
}

private class Textos(ingles: Boolean) {
    val abrir = if (ingles) "Open in NearHouse" else "Ver en NearHouse"
    val sinApp = if (ingles) "To see the full listing and contact the advertiser, install the NearHouse app."
        else "Para ver el anuncio completo y contactar al anunciante, instala la app NearHouse."
    val noDisponible = if (ingles) "This listing is no longer available" else "Este anuncio ya no está disponible"
    val noDisponibleDetalle = if (ingles) "It may have been paused or removed by the advertiser."
        else "Es posible que el anunciante lo haya pausado o eliminado."
}

/** Mismos colores de la app; sigue el modo claro u oscuro del celular. */
private fun estilos() = """<style>
    :root { --fondo:#F4F6F9; --tarjeta:#FFFFFF; --texto:#1B2430; --suave:#677282; --primario:#0B2C4D; --sobrePrimario:#FFFFFF; }
    @media (prefers-color-scheme: dark) {
      :root { --fondo:#0F1318; --tarjeta:#161B22; --texto:#F1F5F9; --suave:#94A3B8; --primario:#3880FF; --sobrePrimario:#FFFFFF; }
    }
    * { box-sizing: border-box; }
    body { margin:0; padding:16px; min-height:100vh; background:var(--fondo); color:var(--texto);
           font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; display:flex; flex-direction:column; align-items:center; }
    .tarjeta { width:100%; max-width:480px; background:var(--tarjeta); border-radius:16px; overflow:hidden; margin-top:8px;
               box-shadow: 0 1px 3px rgba(0,0,0,.12); }
    .foto { display:block; width:100%; aspect-ratio: 4 / 3; object-fit:cover; background:var(--fondo); }
    .cuerpo { padding:20px; }
    .etiqueta { margin:0 0 6px; font-size:13px; color:var(--suave); text-transform:uppercase; letter-spacing:.04em; }
    h1 { margin:0 0 8px; font-size:20px; line-height:1.3; }
    .precio { margin:0 0 6px; font-size:22px; font-weight:700; color:var(--primario); }
    .lugar { margin:0 0 20px; font-size:14px; color:var(--suave); }
    .boton { display:block; text-align:center; text-decoration:none; background:var(--primario); color:var(--sobrePrimario);
             font-weight:700; padding:14px; border-radius:12px; }
    .nota { display:none; margin:14px 0 0; font-size:13px; color:var(--suave); text-align:center; }
    .nota.visible { display:block; }
    .marca { margin-top:16px; font-size:13px; color:var(--suave); }
  </style>"""

/** Escapa el texto para meterlo en HTML (títulos y colonias los escribe el usuario). */
private fun html(texto: String): String = buildString(texto.length) {
    for (c in texto) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }
}

package com.equipo4.nearhome.ui.createlisting

import android.content.Context
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.equipo4.nearhome.domain.model.ListingType
import com.equipo4.nearhome.domain.model.PropertyType
import com.equipo4.nearhome.ui.home.filter.ALL_COLONIAS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import com.equipo4.nearhome.data.local.SessionManager
import com.equipo4.nearhome.di.NetworkModule
import com.equipo4.nearhome.network.PublicacionesApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MultipartBody
import java.io.IOException

/** Un solo ViewModel para todo el flujo (se comparte con el sub-gráfico de navegación). */
class CreateListingViewModel(application: Application) : AndroidViewModel(application) {

    private val session = SessionManager(application)
    private val api: PublicacionesApi = NetworkModule.provideRetrofit(
        NetworkModule.provideOkHttpClient(session)
    ).create(PublicacionesApi::class.java)

    private val _state = MutableStateFlow(CreateListingState())
    val state: StateFlow<CreateListingState> = _state.asStateFlow()

    // ---------------- Paso 1 ----------------

    fun update(touch: String? = null, block: (CreateListingState) -> CreateListingState) {
        _state.update { s ->
            val next = block(s)
            if (touch != null) next.copy(touched = next.touched + touch) else next
        }
    }

    fun touch(key: String) = _state.update { it.copy(touched = it.touched + key) }

    /** "Mascotas permitidas" solo existe al rentar: al comprar se limpia para no guardar un valor oculto. */
    fun setListingType(type: ListingType) = _state.update {
        it.copy(
            listingType = type,
            petsAllowed = if (type == ListingType.VENTA) false else it.petsAllowed
        )
    }

    /** Conserva precio, superficie y colonia (y fotos/ubicación); borra los campos que ya no aplican. */
    fun setPropertyType(type: PropertyType) = _state.update { s ->
        if (s.propertyType == type) s
        else s.copy(
            propertyType = type,
            bedrooms = null, bathrooms = null, parking = null, floor = null, levels = null,
            furnished = false, petsAllowed = false, elevator = false, garden = false,
            landUse = null,
            touched = s.touched.filter { it in setOf("price", "area", "colonia") }.toSet()
        )
    }

    // ---------------- Paso 2 ----------------

    fun addPhoto(path: String) = _state.update {
        if (it.photos.size >= 5) it else it.copy(photos = it.photos + path)
    }

    fun removePhoto(path: String) {
        File(path).delete()
        _state.update { s ->
            val left = s.photos - path
            if (left.isEmpty()) {
                // Sin fotos se descarta la ubicación: se vuelve a tomar con la siguiente primera foto.
                s.copy(
                    photos = left, latitude = null, longitude = null,
                    street = "", streetNumber = "", postalCode = "",
                    locationColonia = null,
                    locationConfirmed = false, locationLoading = false, locationError = false
                )
            } else s.copy(photos = left)
        }
    }

    /** Ubicación GPS al tomar la primera foto. Corre aquí para sobrevivir a girar el celular. */
    fun locate(context: Context) {
        val app = context.applicationContext
        viewModelScope.launch {
            _state.update { it.copy(locationLoading = true, locationError = false) }
            val loc = LocationHelper.current(app)
            if (loc == null) {
                _state.update { it.copy(locationLoading = false, locationError = true) }
            } else {
                resolve(app, loc.latitude, loc.longitude)
            }
        }
    }

    /** Al soltar el pin se recalcula la dirección (y la colonia) y hay que volver a confirmar. */
    fun onPinMoved(context: Context, lat: Double, lng: Double) {
        val app = context.applicationContext
        viewModelScope.launch { resolve(app, lat, lng) }
    }

    private suspend fun resolve(app: Context, lat: Double, lng: Double) {
        val a = LocationHelper.reverseGeocode(app, lat, lng)
        // Se intenta reconocer la colonia con todos los hints del geocoder.
        // Si no hay coincidencia confiable, queda en null y la pantalla pide elegirla a mano.
        val matched = a?.let { ColoniaMatcher.match(it.hints, ALL_COLONIAS) }
        _state.update {
            if (it.photos.isEmpty()) it   // el usuario borró todas las fotos mientras cargaba
            else it.copy(
                latitude = lat, longitude = lng,
                street = a?.street.orEmpty(),
                streetNumber = a?.number.orEmpty(),
                postalCode = a?.postalCode.orEmpty(),
                locationColonia = matched,
                colonia = matched ?: it.colonia,
                locationConfirmed = false, locationLoading = false, locationError = false
            )
        }
    }

    /** Colonia elegida a mano cuando el geocoder no la reconoció (o al corregir). */
    fun setLocationColonia(colonia: String) = _state.update {
        it.copy(
            locationColonia = colonia,
            colonia = colonia,
            locationConfirmed = false
        )
    }

    fun updateAddress(street: String? = null, number: String? = null, postalCode: String? = null) =
        _state.update {
            it.copy(
                street = street ?: it.street,
                streetNumber = number ?: it.streetNumber,
                postalCode = postalCode ?: it.postalCode,
                locationConfirmed = false
            )
        }

    fun confirmLocation() = _state.update {
        if (it.hasLocation && it.locationColonia != null &&
            it.street.isNotBlank() && it.streetNumber.isNotBlank()
        ) it.copy(locationConfirmed = true) else it
    }

    // ---------------- Paso 3 ----------------

    fun setContact(contact: ContactoUsuario) = _state.update { it.copy(contact = contact) }

    // ---------------- Envío ----------------

    /** Publica realmente el anuncio y envía las fotos como multipart/form-data. */
    fun submit() {
        if (_state.value.submitting || _state.value.submitted) return
        val snapshot = _state.value
        if (!snapshot.isStep1Valid || !snapshot.isStep2Valid || !snapshot.isStep3Valid) {
            _state.update { it.copy(submitError = "Revisa los datos obligatorios antes de enviar.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, submitError = null) }
            try {
                val listing = _state.value
                val coloniasResponse = api.colonias()
                if (!coloniasResponse.isSuccessful) throw IOException("No se pudieron consultar las colonias (${coloniasResponse.code()}).")
                val colonia = coloniasResponse.body().orEmpty().firstOrNull {
                    it.nombre.trim().equals(listing.locationColonia ?: listing.colonia.orEmpty(), ignoreCase = true)
                } ?: throw IOException("La colonia seleccionada no existe en el catálogo de la base de datos. Verifica que esté registrada en PostgreSQL.")
                val tipo = when (listing.propertyType) {
                    PropertyType.DEPARTAMENTO -> "DEPARTAMENTO"
                    PropertyType.CASA -> "CASA"
                    PropertyType.TERRENO -> "TERRENO"
                    null -> throw IOException("Selecciona el tipo de propiedad.")
                }
                val modalidad = when (listing.listingType) {
                    ListingType.VENTA -> "Venta"
                    ListingType.RENTA -> "Renta"
                    null -> throw IOException("Selecciona venta o renta.")
                }
                val json = """{"titulo":${quote(listing.title.trim())},"descripcion":${quote(listing.description.trim())},"precio":${listing.price.toDouble()},"modalidad":${quote(modalidad)},"tipo":${quote(tipo)},"calle":${quote(listing.street.trim())},"numero":${quote(listing.streetNumber.trim())},"latitud":${listing.latitude},"longitud":${listing.longitude},"idColonia":${colonia.id},"superficie":${listing.area.toInt()},"codigoPostal":${listing.postalCode.takeIf { it.matches(Regex("^[0-9]{5}$")) }?.let(::quote) ?: "null"},"amueblado":${listing.furnished},"dormitorios":${listing.bedrooms ?: 0},"banos":${listing.bathrooms ?: 0},"mascotas":${listing.petsAllowed},"cochera":${listing.parking ?: 0},"patio":${listing.garden},"piso":${listing.floor ?: "null"},"elevador":${listing.elevator},"niveles":${listing.levels ?: "null"},"usoSuelo":${listing.landUse?.name?.let(::quote) ?: "null"},"reglas":${listing.rules.trim().takeIf { it.isNotEmpty() }?.let(::quote) ?: "null"}}"""
                val datos = json.toRequestBody("application/json; charset=utf-8".toMediaType())
                val parts = listing.photos.mapIndexed { index, path ->
                    val file = File(path)
                    if (!file.exists() || !file.isFile) throw IOException("No se encuentra la foto ${index + 1}. Vuelve a seleccionarla.")
                    val mime = when (file.extension.lowercase()) { "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }
                    MultipartBody.Part.createFormData("fotos", file.name, file.readBytes().toRequestBody(mime.toMediaType()))
                }
                val response = api.crear(datos, parts)
                if (!response.isSuccessful) {
                    val body = response.errorBody()?.string().orEmpty()
                    throw IOException(parseApiError(body).ifBlank { "No se pudo publicar el anuncio (HTTP ${response.code()})." })
                }
                _state.update { it.copy(submitting = false, submitted = true, submitError = null) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                _state.update { it.copy(submitting = false, submitted = false, submitError = e.message ?: "Ocurrió un error al publicar.") }
            }
        }
    }

    private fun quote(value: String): String = com.google.gson.Gson().toJson(value)
    private fun parseApiError(body: String): String = try {
        val obj = com.google.gson.JsonParser.parseString(body).asJsonObject
        val error = obj.get("error")?.asString.orEmpty()
        val details = obj.getAsJsonArray("detalles")?.mapNotNull { runCatching { it.asString }.getOrNull() }.orEmpty()
        (listOf(error).filter { it.isNotBlank() } + details).joinToString("\n")
    } catch (_: Exception) { body.take(500) }

    // ---------------- General ----------------

    /** Descartar: borra las fotos del disco y limpia todo. */
    fun reset() {
        _state.value.photos.forEach { File(it).delete() }
        _state.value = CreateListingState()
    }
}
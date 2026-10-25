package com.equipo4.nearhome.ui.profile.seller

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.equipo4.nearhome.data.local.SessionManager
import com.equipo4.nearhome.di.NetworkModule
import com.equipo4.nearhome.domain.model.ListingType
import com.equipo4.nearhome.domain.model.Property
import com.equipo4.nearhome.domain.model.PropertyType
import com.equipo4.nearhome.network.PublicacionesApi
import com.equipo4.nearhome.ui.profile.UserRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MyListingsViewModel(application: Application) : AndroidViewModel(application) {
    private val session = SessionManager(application)
    private val api: PublicacionesApi = NetworkModule.provideRetrofit(
        NetworkModule.provideOkHttpClient(session)
    ).create(PublicacionesApi::class.java)

    private val _uiState = MutableStateFlow(
        MyListingsUiState(
            userName = session.getUserName().orEmpty(),
            userEmail = session.getUserEmail().orEmpty(),
            userPhone = session.getUserPhone().orEmpty(),
            userPhotoUrl = null,
            role = UserRole.fromDb(session.getUserRole())
        )
    )
    val uiState: StateFlow<MyListingsUiState> = _uiState.asStateFlow()

    init { loadMyListings() }

    fun loadMyListings() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val response = api.mias()
                if (!response.isSuccessful) {
                    val message = when (response.code()) {
                        401 -> "Tu sesión venció. Inicia sesión nuevamente."
                        403 -> "Tu cuenta no tiene permiso para consultar anuncios."
                        else -> "No se pudieron cargar tus anuncios (HTTP ${response.code()})."
                    }
                    throw IllegalStateException(message)
                }
                val items = response.body().orEmpty().map { dto ->
                    val status = when (dto.estado.uppercase()) {
                        "PUBLICADA", "PUBLICADO", "ACTIVA", "ACTIVO" -> ListingStatus.PUBLISHED
                        "RECHAZADA", "RECHAZADO" -> ListingStatus.REJECTED
                        else -> ListingStatus.IN_REVIEW
                    }
                    val type = when (dto.tipo.uppercase()) {
                        "CASA" -> PropertyType.CASA
                        "TERRENO" -> PropertyType.TERRENO
                        else -> PropertyType.DEPARTAMENTO
                    }
                    val property = Property(
                        id = dto.id.toString(), title = dto.titulo, price = dto.precio,
                        listingType = if (dto.modalidad.equals("Renta", true)) ListingType.RENTA else ListingType.VENTA,
                        location = listOf(dto.calle, dto.numero, dto.colonia, "Manzanillo, Colima").filter { it.isNotBlank() }.joinToString(", "),
                        type = type, areaSqM = dto.superficie, bedrooms = dto.dormitorios,
                        bathrooms = dto.banos, garages = dto.cochera, images = emptyList(),
                        latitude = dto.latitud, longitude = dto.longitud, colonia = dto.colonia,
                        niveles = dto.niveles, piso = dto.piso, elevador = dto.elevador ?: false,
                        usoSuelo = dto.usoSuelo
                    )
                    MyListingItem(property, status)
                }
                _uiState.update { it.copy(listings = items, isLoading = false, error = null) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Error al cargar los anuncios.") }
            }
        }
    }
}

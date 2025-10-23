package com.example.trackpersonal.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.trackpersonal.location.LocationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LocationViewModel(context: Context) : ViewModel() {

    private val repo = LocationRepository(context)

    private val _state = MutableStateFlow(LocationUiState())
    val state: StateFlow<LocationUiState> = _state

    fun start(highAccuracy: Boolean = true, intervalMillis: Long = 2000L, minDistanceMeters: Float = 1f) {
        viewModelScope.launch {
            repo.start(highAccuracy, intervalMillis, minDistanceMeters)
            // observe location stream
            viewModelScope.launch {
                repo.locations.collectLatest { loc ->
                    _state.value = LocationUiState(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        accuracyMeters = loc.accuracy,
                        provider = loc.provider
                    )
                }
            }
        }
    }

    fun stop() {
        repo.stop()
    }
}

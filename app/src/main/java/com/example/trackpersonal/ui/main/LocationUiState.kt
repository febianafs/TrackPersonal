package com.example.trackpersonal.ui.main

data class LocationUiState(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val provider: String? = null
)

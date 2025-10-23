package com.example.trackpersonal.location

import android.content.Context
import android.location.Location
import kotlinx.coroutines.flow.Flow

class LocationRepository(context: Context) {
    private val client: LocationClient = DefaultLocationClient(context.applicationContext)

    val locations: Flow<Location> = client.locations
    suspend fun start(highAccuracy: Boolean = true, intervalMillis: Long = 2000L, minDistanceMeters: Float = 1f) =
        client.start(highAccuracy, intervalMillis, minDistanceMeters)

    fun stop() = client.stop()
    suspend fun lastKnown(): Location? = client.lastKnown()
}
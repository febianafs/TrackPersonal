package com.example.trackpersonal.location

import android.location.Location
import kotlinx.coroutines.flow.Flow

interface LocationClient {
    val locations: Flow<Location>
    suspend fun start(highAccuracy: Boolean = true, intervalMillis: Long = 2000L, minDistanceMeters: Float = 1f)
    fun stop()
    suspend fun lastKnown(): Location?
}
package com.example.trackpersonal.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.tasks.await

class DefaultLocationClient(
    private val context: Context
) : LocationClient {

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(context) }

    private var request: LocationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, 2000L
    ).setMinUpdateIntervalMillis(1000L)
        .setMinUpdateDistanceMeters(1f)
        .setWaitForAccurateLocation(true)
        .setGranularity(Granularity.GRANULARITY_FINE)
        .build()

    private var callback: LocationCallback? = null
    private val _bus = MutableSharedFlow<Location>(replay = 1)
    override val locations: Flow<Location> = _bus

    @SuppressLint("MissingPermission")
    override suspend fun start(
        highAccuracy: Boolean,
        intervalMillis: Long,
        minDistanceMeters: Float
    ) {
        request = LocationRequest.Builder(
            if (highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            intervalMillis
        ).setMinUpdateIntervalMillis(intervalMillis.coerceAtLeast(1000L))
            .setMinUpdateDistanceMeters(minDistanceMeters)
            .setWaitForAccurateLocation(highAccuracy)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .build()

        if (callback == null) {
            callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { loc ->
                        _bus.tryEmit(loc)
                    }
                }
            }
        }

        fused.requestLocationUpdates(request, callback!!, Looper.getMainLooper())

        // Emit last known location (jika ada) TANPA memaksa .result
        try {
            lastKnown()?.let { _bus.emit(it) }
        } catch (_: Exception) {
            // abaikan; kalau gagal ya tunggu update berikutnya dari requestLocationUpdates
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun lastKnown(): Location? {
        // Gunakan await() agar suspend sampai Task selesai
        return try {
            fused.lastLocation.await()
        } catch (e: Exception) {
            null
        }
    }

    override fun stop() {
        callback?.let { fused.removeLocationUpdates(it) }
    }
}

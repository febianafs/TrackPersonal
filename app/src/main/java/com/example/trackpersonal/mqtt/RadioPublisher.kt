package com.example.trackpersonal.mqtt

import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import com.example.trackpersonal.utils.SecurePref
import com.google.gson.Gson
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.atan2
import kotlin.math.sqrt

class RadioPublisher(
    private val context: Context,
    private val pref: SecurePref,
    private val client: Mqtt5AsyncClient
) {
    private val gson = Gson()

    private var lastLat: Double? = null
    private var lastLng: Double? = null
    private var lastSendAt = 0L

    private val minMeters = 25.0
    private val minMillis = 10_000L

    suspend fun publishRadioDataIfNeeded() {
        val loc = LatestLocationStore.getLastLocation(context) ?: return
        val now = SystemClock.elapsedRealtime()

        val movedEnough = lastLat == null || lastLng == null ||
                haversineMeters(lastLat!!, lastLng!!, loc.latitude, loc.longitude) > minMeters
        val timeEnough = (now - lastSendAt) >= minMillis

        if (!movedEnough && !timeEnough) return

        lastLat = loc.latitude
        lastLng = loc.longitude
        lastSendAt = now

        val payload = buildDataPayload(
            lat = loc.latitude,
            lng = loc.longitude,
            gpsTs = (System.currentTimeMillis() / 1000L),
            hr = HeartRateSource.get() ?: 0,
            hrTs = (System.currentTimeMillis() / 1000L),
            battery = getBatteryLevel()
        )
        publishJson("radio/data", payload, qos = 1, retain = false)
    }

    suspend fun publishSos(active: Boolean) {
        val userId = pref.getUserId()?.toString() ?: "unknown"
        val name = pref.getFullName() ?: pref.getName() ?: "Unknown"
        val avatar = pref.getAvatarUrl()
        val serial = pref.getAndroidId().orEmpty()
        val loc = LatestLocationStore.getLastLocation(context)
        val payload = mapOf(
            "timestamp" to (System.currentTimeMillis() / 1000L),
            "serial_number" to serial,
            "id" to userId,
            "name" to name,
            "avatar" to (avatar ?: ""),
            "sos" to if (active) 1 else 0,
            "latitude" to (loc?.latitude ?: 0.0),
            "longitude" to (loc?.longitude ?: 0.0)
        )
        // QoS 2 retained = last known state selalu tersedia
        publishJson("radio/sos", payload, qos = 2, retain = true)
    }

    private fun buildDataPayload(
        lat: Double,
        lng: Double,
        gpsTs: Long,
        hr: Int,
        hrTs: Long,
        battery: Int
    ): Map<String, Any> {
        val userId = pref.getUserId()?.toString() ?: "unknown"
        val serial = pref.getAndroidId().orEmpty()
        val identity = mapOf(
            "id" to userId,
            "nrp" to (pref.getSatuan() ?: ""), // ganti ke NRP asli kalau ada fieldnya
            "name" to (pref.getFullName() ?: pref.getName() ?: "Unknown"),
            "rank" to (pref.getRank() ?: ""),
            "unit" to (pref.getSatuan() ?: ""),
            "battalion" to (pref.getBatalyon() ?: ""),
            "squad" to (pref.getRegu() ?: ""),
            "avatar" to (pref.getAvatarUrl() ?: "")
        )
        return mapOf(
            "timestamp" to (System.currentTimeMillis() / 1000L),
            "serial_number" to serial,
            "identity" to identity,
            "gps" to mapOf(
                "latitude" to lat,
                "longitude" to lng,
                "gps_timestamp" to gpsTs
            ),
            "radio_health" to mapOf(
                "heartrate" to hr,
                "heartrate_timestamp" to hrTs
            ),
            "battery" to mapOf("level" to battery)
        )
    }

    private fun getBatteryLevel(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return max(0, level)
    }

    private suspend fun publishJson(topic: String, body: Any, qos: Int, retain: Boolean) {
        val payload = gson.toJson(body).toByteArray()
        suspendCancellableCoroutine { cont ->
            client.publishWith()
                .topic(topic)
                .qos(
                    when (qos) {
                        2 -> MqttQos.EXACTLY_ONCE
                        1 -> MqttQos.AT_LEAST_ONCE
                        else -> MqttQos.AT_MOST_ONCE
                    }
                )
                .retain(retain)
                .payload(payload)
                .send()
                .whenComplete { _, _ -> cont.resume(Unit) }
        }
    }
}

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return R * c
}

object HeartRateSource { fun get(): Int? = null }
object LatestLocationStore {
    @Volatile private var last: android.location.Location? = null
    fun setLastLocation(l: android.location.Location) { last = l }
    fun getLastLocation(ctx: Context): android.location.Location? = last
}

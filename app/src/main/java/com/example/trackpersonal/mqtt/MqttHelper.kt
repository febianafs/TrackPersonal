package com.example.trackpersonal.mqtt

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat
import android.Manifest
import android.content.pm.PackageManager
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.MqttWebSocketConfig
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.connect.Mqtt3Connect
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAck
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

class MqttHelper(
    private val context: Context,
    private val onMessage: (String) -> Unit = {}
) {
    private val TAG = "MQTT-HiveMQ"

    // device identifier stabil
    private val serialNumber = getDeviceIdentifier()

    @Volatile private var isConnecting = false
    @Volatile private var isConnected = false
    private var listenerRegistered = false

    // ====== QUEUE OFFLINE (bounded) ======
    private val sendQueue = ConcurrentLinkedQueue<QueuedMsg>()
    private data class QueuedMsg(
        val topic: String,
        val payload: ByteArray,
        val qos: MqttQos,
        val retain: Boolean
    )
    private val MAX_QUEUE = 500  // <-- Step 7: batasi antrian agar tidak membengkak

    // ===== Ambil IMEI / ANDROID_ID aman =====
    @SuppressLint("MissingPermission", "HardwareIds")
    private fun getDeviceIdentifier(): String {
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
                return getAndroidId()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val method = TelephonyManager::class.java.getMethod("getImei")
                val imei = method.invoke(tm) as? String
                if (!imei.isNullOrEmpty()) return imei
            } else {
                @Suppress("DEPRECATION")
                tm.deviceId?.let { return it }
            }
            val serial = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Build.getSerial()
            } else {
                @Suppress("DEPRECATION")
                Build.SERIAL
            }
            if (!serial.isNullOrEmpty() && serial != "unknown") return serial
        } catch (_: Exception) { /* ignore */ }
        return getAndroidId()
    }

    @SuppressLint("HardwareIds")
    private fun getAndroidId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

    // ===== MQTT client via WebSocket =====
    val client: Mqtt3AsyncClient = MqttClient.builder()
        .identifier("android-client-$serialNumber")
        .useMqttVersion3()
        .serverHost("147.139.161.159")
        .serverPort(9001) // WebSocket
        .webSocketConfig(MqttWebSocketConfig.builder().build())
        .automaticReconnectWithDefaultConfig() // auto-reconnect
        .buildAsync()

    // ===== CONNECT (debounced) =====
    @Synchronized
    fun connect() {
        if (client.state.isConnected) {
            if (!isConnected) {
                isConnected = true
                flushQueue()
            }
            if (!listenerRegistered) setupMessageListener()
            return
        }
        if (isConnecting) return
        isConnecting = true
        Log.d(TAG, "🔄 Connecting ke MQTT broker...")

        val connectMsg = Mqtt3Connect.builder()
            .keepAlive(20) // lebih sering ping → tahan putus
            .simpleAuth().username("kodam").password("kodam2025".toByteArray()).applySimpleAuth()
            .cleanSession(true)
            .build()

        client.connect(connectMsg).whenComplete { _: Mqtt3ConnAck?, throwable: Throwable? ->
            isConnecting = false
            if (throwable != null) {
                Log.e(TAG, "❌ MQTT connect error: ${throwable.message}")
                isConnected = false
            } else {
                Log.d(TAG, "✅ MQTT connected via WebSocket")
                isConnected = true
                setupMessageListener()
                flushQueue()
            }
        }
    }

    private fun setupMessageListener() {
        if (listenerRegistered) return
        client.publishes(MqttGlobalPublishFilter.ALL) { publish ->
            val msg = publish.payloadAsBytes?.toString(Charsets.UTF_8) ?: ""
            Log.d(TAG, "📩 Pesan dari ${publish.topic}: $msg")
            try { onMessage(msg) } catch (_: Exception) {}
        }
        listenerRegistered = true
    }

    private fun flushQueue() {
        if (!isConnected || !client.state.isConnected) return
        while (true) {
            val item = sendQueue.poll() ?: break
            client.publishWith()
                .topic(item.topic)
                .payload(item.payload)
                .qos(item.qos)
                .retain(item.retain)
                .send()
                .whenComplete { _, t ->
                    if (t != null) Log.e(TAG, "❌ Flush gagal → ${item.topic}", t)
                    else Log.d(TAG, "📡 Flushed → ${item.topic} (${item.payload.size} bytes)")
                }
        }
    }

    private fun offerOrSend(topic: String, payload: JSONObject, qos: MqttQos, retain: Boolean) {
        val bytes = payload.toString().toByteArray()
        val nowConnected = client.state.isConnected && isConnected
        if (nowConnected) {
            client.publishWith()
                .topic(topic)
                .payload(bytes)
                .qos(qos)
                .retain(retain)
                .send()
                .whenComplete { _, t ->
                    if (t != null) Log.e(TAG, "❌ Publish ke $topic gagal", t)
                    else Log.d(TAG, "📡 Published → $topic : $payload (qos=$qos, retain=$retain)")
                }
        } else {
            Log.w(TAG, "⚠️ Belum connect. Queue & reconnect… topic=$topic")
            // ==== Step 7: drop-oldest jika queue penuh ====
            if (sendQueue.size >= MAX_QUEUE) {
                val dropped = sendQueue.poll()
                Log.w(TAG, "🗑️ Queue full ($MAX_QUEUE). Dropping oldest → ${dropped?.topic}")
            }
            sendQueue.offer(QueuedMsg(topic, bytes, qos, retain))
            connect() // aman (debounced)
        }
    }

    // ===== API publish =====
    fun publishData(
        id: String,
        nrp: String,
        name: String,
        rank: String,
        unit: String,
        battalion: String,
        squad: String,
        avatar: String,
        latitude: Double,
        longitude: Double,
        gpsTimestamp: Long,
        heartrate: Int,
        heartrateTimestamp: Long,
        batteryLevel: Int,
        timestamp: Long
    ) {
        val topic = "radio/data" // tetap single topic (tanpa /#)
        val payload = JSONObject().apply {
            put("timestamp", timestamp)
            put("serial_number", serialNumber)
            put("identity", JSONObject().apply {
                put("id", id)
                put("nrp", nrp)
                put("name", name)
                put("rank", rank)
                put("unit", unit)
                put("battalion", battalion)
                put("squad", squad)
                put("avatar", avatar)
            })
            put("gps", JSONObject().apply {
                put("latitude", latitude)
                put("longitude", longitude)
                put("gps_timestamp", gpsTimestamp)
            })
            put("radio_health", JSONObject().apply {
                put("heartrate", heartrate)
                put("heartrate_timestamp", heartrateTimestamp)
            })
            put("battery", JSONObject().apply {
                put("level", batteryLevel)
            })
        }
        offerOrSend(topic, payload, MqttQos.AT_LEAST_ONCE, retain = false)
    }

    fun publishSOS(
        id: String,
        name: String,
        avatar: String,
        sos: Int,
        lat: Double,
        lng: Double,
        timestamp: Long
    ) {
        val topic = "radio/sos"
        val payload = JSONObject().apply {
            put("timestamp", timestamp)
            put("serial_number", serialNumber)
            put("id", id)
            put("name", name)
            put("avatar", avatar)
            put("sos", sos)
            put("latitude", lat)
            put("longitude", lng)
        }
        // retained → state terakhir terbaca subscriber baru
        offerOrSend(topic, payload, MqttQos.EXACTLY_ONCE, retain = true)
    }

    fun disconnect() {
        try {
            if (client.state.isConnected) client.disconnect()
        } catch (_: Exception) {}
        isConnected = false
    }
}

package com.example.trackpersonal.mqtt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.trackpersonal.R
import com.example.trackpersonal.utils.SecurePref
import com.google.gson.Gson
import com.hivemq.client.mqtt.datatypes.MqttQos
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class MqttService : Service() {

    companion object {
        private const val CHANNEL_ID = "mqtt_tracking"
        private const val NOTIF_ID = 1001

        private const val ACTION_START = "MQTT_START"
        private const val ACTION_STOP = "MQTT_STOP"
        private const val ACTION_SOS = "MQTT_SOS"
        private const val EXTRA_SOS_ACTIVE = "sos_active"

        fun start(context: Context) {
            val i = Intent(context, MqttService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, MqttService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }

        fun sendSos(context: Context, active: Boolean) {
            val i = Intent(context, MqttService::class.java)
                .setAction(ACTION_SOS)
                .putExtra(EXTRA_SOS_ACTIVE, active)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }

    private lateinit var pref: SecurePref
    private lateinit var mqtt: MqttEngine
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val running = AtomicBoolean(false)
    private var dataJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> ensureStarted()
            ACTION_STOP -> stopSelf()
            ACTION_SOS -> {
                val active = intent.getBooleanExtra(EXTRA_SOS_ACTIVE, false)
                scope.launch { publishSos(active) }
            }
            else -> ensureStarted()
        }
        return START_STICKY
    }

    private fun ensureStarted() {
        if (running.get()) return

        pref = SecurePref(this)
        createNotifChannel()
        startForeground(NOTIF_ID, buildNotif("Connecting…"))

        val userId = pref.getUserId()?.toString() ?: "u-unknown"
        val androidId = pref.getAndroidId().orEmpty()
        val clientId = "android-$userId-$androidId".take(22)

        mqtt = MqttEngine(
            host = "147.139.161.159",
            portWs = 9001,
            username = "kodam",
            password = "kodam2025",
            clientId = clientId
        )

        val lwtTopic = "radio/status/$userId"
        val lwtOffline = Gson().toJson(mapOf("online" to 0, "ts" to (System.currentTimeMillis() / 1000)))
        val onlineMsg = Gson().toJson(mapOf("online" to 1, "ts" to (System.currentTimeMillis() / 1000)))

        mqtt.connectWithLwt(lwtTopic, lwtOffline)
            .whenComplete { _, _ ->
                mqtt.client.publishWith()
                    .topic(lwtTopic)
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .retain(true)
                    .payload(onlineMsg.toByteArray())
                    .send()
                updateNotif("Connected")
                startDataLoop()
            }
    }

    private fun startDataLoop() {
        if (running.getAndSet(true)) return
        dataJob = scope.launch {
            val publisher = RadioPublisher(this@MqttService, pref, mqtt.client)
            while (isActive && running.get()) {
                try { publisher.publishRadioDataIfNeeded() } catch (_: Exception) {}
                delay(10_000L)
            }
        }
    }

    private suspend fun publishSos(active: Boolean) {
        val publisher = RadioPublisher(this@MqttService, pref, mqtt.client)
        publisher.publishSos(active) // QoS 2 + retained
    }

    private fun updateNotif(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotif(text))
    }

    private fun buildNotif(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_koopsus) // pastikan drawable ini ada
            .setContentTitle("Radio Tracking")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "MQTT Tracking", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        running.set(false)
        dataJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

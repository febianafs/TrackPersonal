package com.example.trackpersonal.mqtt

import android.app.*
import android.content.*
import android.location.Location
import android.net.*
import android.net.wifi.WifiManager
import android.os.*
import android.os.BatteryManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.trackpersonal.R
import com.example.trackpersonal.utils.SecurePref
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class MqttService : Service() {

    companion object {
        private const val CHANNEL_ID = "mqtt_tracking"
        private const val NOTIF_ID = 2001

        private const val ACTION_START = "MQTT_START"
        private const val ACTION_STOP  = "MQTT_STOP"
        private const val ACTION_SOS   = "MQTT_SOS"
        private const val EXTRA_SOS_ACTIVE = "sos_active"

        // Broadcast internal untuk ubah interval
        const val ACTION_INTERVAL_CHANGED = "MQTT_INTERVAL_CHANGED"

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
    private lateinit var fused: FusedLocationProviderClient
    private val mqtt: MqttHelper by lazy { MqttHelper(this) { /* optional */ } }

    // lokasi terakhir
    private var lastKnown: Location? = null

    // HR + Battery realtime
    private var latestHeartRate: Int = 0
    private var latestHeartTs: Long = 0
    private var latestBatteryPercent: Int = 0

    // loop periodik (dibaca dari SecurePref)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var tickerJob: Job? = null
    private var periodMs: Long = 10_000L

    // network callback
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    // WiFiLock agar Wi-Fi tidak tidur
    private var wifiLock: WifiManager.WifiLock? = null

    // battery receiver (sticky)
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) {
                latestBatteryPercent = (level * 100) / scale
            }
        }
    }

    // receiver untuk perubahan interval
    private val intervalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_INTERVAL_CHANGED) return
            val newPeriod = readPeriodFromPref()
            if (newPeriod != periodMs) {
                periodMs = newPeriod
                restartTicker()
                updateNotif("Sending every ${periodMs / 1000}s…")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SOS -> {
                val active = intent.getBooleanExtra(EXTRA_SOS_ACTIVE, false)
                scope.launch { publishSos(active) }
                return START_STICKY
            }
            else -> ensureStarted()
        }
        return START_STICKY
    }

    private fun ensureStarted() {
        if (running.get()) return
        running.set(true)

        pref = SecurePref(this)
        createNotifChannel()
        startForeground(NOTIF_ID, buildNotif("Connecting…"))

        // Ambil WiFiLock → cegah Wi-Fi tidur saat screen off
        acquireWifiLock()

        // battery realtime (siaran sistem → EXPORTED)
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(batteryReceiver, batteryFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(batteryReceiver, batteryFilter)
        }

        // listen perubahan interval (siaran internal app → NOT_EXPORTED)
        val intervalFilter = IntentFilter(ACTION_INTERVAL_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(intervalReceiver, intervalFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(intervalReceiver, intervalFilter)
        }

        fused = LocationServices.getFusedLocationProviderClient(this)
        startLocationUpdates()

        registerNetworkCallback()
        mqtt.connect() // soft ensure

        // Baca interval awal dari pref
        periodMs = readPeriodFromPref()
        updateNotif("Sending every ${periodMs / 1000}s…")

        startTicker()
    }

    private fun readPeriodFromPref(): Long {
        val userKey = pref.getCurrentUserKey()
        val seconds = pref.getMqttIntervalSecondsForUser(userKey, defaultSeconds = 10)
        return (seconds.coerceAtLeast(1)).toLong() * 1000L
    }

    private fun acquireWifiLock() {
        try {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi.createWifiLock(mode, "mqtt-wifi-lock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) { /* ignore */ }
    }

    private fun releaseWifiLock() {
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                try { mqtt.connect() } catch (_: Exception) {} // debounced di helper
                refreshLatestHeartFromPref()
                sendRadioDataOnce()
                delay(periodMs)
            }
        }
    }

    private fun restartTicker() {
        stopTicker()
        startTicker()
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private suspend fun publishSos(active: Boolean) {
        val id = pref.getUserId()?.toString() ?: "unknown"
        val name = pref.getFullName() ?: pref.getName() ?: "Unknown"
        val avatar = pref.getAvatarUrl() ?: ""

        val loc = lastKnown ?: Location("placeholder").apply {
            latitude = 0.0
            longitude = 0.0
            time = System.currentTimeMillis()
        }
        val ts = System.currentTimeMillis() / 1000L

        mqtt.publishSOS(
            id = id,
            name = name,
            avatar = avatar,
            sos = if (active) 1 else 0,
            lat = loc.latitude,
            lng = loc.longitude,
            timestamp = ts
        )
        updateNotif(if (active) "SOS ON • sending…" else "SOS OFF • sending…")
    }

    private fun sendRadioDataOnce() {
        val loc = lastKnown ?: run {
            Log.w("MQTT-HiveMQ", "⚠️ lastKnown null → kirim (0,0). Cek izin background/precise & GPS.")
            Location("placeholder").apply {
                latitude = 0.0
                longitude = 0.0
                time = System.currentTimeMillis()
            }
        }

        val id = pref.getUserId()?.toString() ?: "unknown"
        val nrp = pref.getNrp() ?: ""
        val name = pref.getFullName() ?: pref.getName() ?: "Unknown"
        val rank = pref.getRank() ?: ""
        val unit = pref.getSatuan() ?: ""
        val battalion = pref.getBatalyon() ?: ""
        val squad = pref.getRegu() ?: ""
        val avatar = pref.getAvatarUrl() ?: ""
        val ts = System.currentTimeMillis() / 1000L

        mqtt.publishData(
            id = id,
            nrp = nrp,
            name = name,
            rank = rank,
            unit = unit,
            battalion = battalion,
            squad = squad,
            avatar = avatar,
            latitude = loc.latitude,
            longitude = loc.longitude,
            gpsTimestamp = ts,
            heartrate = latestHeartRate,
            heartrateTimestamp = latestHeartTs,
            batteryLevel = latestBatteryPercent,
            timestamp = ts
        )
        // teks notif ikut interval aktif
        updateNotif("Sending every ${periodMs / 1000}s…")
    }

    // ===== lokasi background =====
    private fun startLocationUpdates() {
        val fine = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return

        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
            .setMinUpdateIntervalMillis(2_000L)
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(true)
            .build()

        fused.requestLocationUpdates(req, locationCallback, mainLooper)
        fused.lastLocation.addOnSuccessListener { loc -> loc?.let { lastKnown = it } }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { lastKnown = it }
        }
    }

    // ===== pantau jaringan =====
    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                try { mqtt.connect() } catch (_: Exception) {}
            }
            override fun onLost(network: Network) {
                updateNotif("Network lost… retrying")
            }
        }
        cm.registerNetworkCallback(req, netCallback!!)
    }

    private fun unregisterNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try { netCallback?.let { cm.unregisterNetworkCallback(it) } } catch (_: Exception) {}
        netCallback = null
    }

    // ===== Notif =====
    private fun buildNotif(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_kodamjaya)
            .setContentTitle("Radio Tracking")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotif(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotif(text))
    }

    private fun createNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "MQTT Tracking", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }

    private fun refreshLatestHeartFromPref() {
        pref.getHeartRateBpm()?.let { latestHeartRate = it }
        pref.getHeartRateTs()?.let { latestHeartTs = it }
    }

    // === Self-heal kalau task di-swipe dari recent apps / proses di-kill ringan ===
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val i = Intent(applicationContext, MqttService::class.java).setAction(ACTION_START)
        val pi = PendingIntent.getService(
            applicationContext, 1001, i,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 1000,
            pi
        )
    }

    override fun onDestroy() {
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(intervalReceiver) } catch (_: Exception) {}
        try { fused.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
        unregisterNetworkCallback()
        stopTicker()
        releaseWifiLock()
        try { mqtt.disconnect() } catch (_: Exception) {}
        scope.cancel()
        running.set(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

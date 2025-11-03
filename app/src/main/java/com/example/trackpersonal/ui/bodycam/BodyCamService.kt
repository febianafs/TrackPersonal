package com.example.trackpersonal.ui.bodycam

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.SurfaceView
import androidx.core.app.NotificationCompat
import com.example.trackpersonal.R
import com.example.trackpersonal.utils.DeviceIdProvider
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.rtplibrary.rtmp.RtmpCamera2
import com.pedro.rtmp.utils.ConnectCheckerRtmp

class BodyCamService : Service(), ConnectCheckerRtmp {

    companion object {
        const val CHANNEL_ID = "bodycam_channel"
        const val NOTIF_ID = 2009
        const val ACTION_START = "BCAM_START"
        const val ACTION_STOP  = "BCAM_STOP"
        const val EXTRA_URL_BASE = "EXTRA_URL_BASE"

        // Broadcast status stream
        const val ACTION_STREAM_STATE = "com.example.trackpersonal.STREAM_STATE"
        const val EXTRA_STREAM_ON = "on"
    }

    private fun sendStreamState(on: Boolean) {
        val i = Intent(BodyCamService.ACTION_STREAM_STATE)
            .setPackage(packageName) // batasi hanya untuk app ini
            .putExtra(BodyCamService.EXTRA_STREAM_ON, on)
        sendBroadcast(i)
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): BodyCamService = this@BodyCamService
    }

    private var rtmpCamera: RtmpCamera2? = null
    private var isStreaming = false
    private var currentBitrate = 2_000_000

    // Bisa di-override lewat EXTRA_URL_BASE
    private var rtmpBase = "rtmp://147.139.161.159:22935/personel"

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                intent.getStringExtra(EXTRA_URL_BASE)?.let { rtmpBase = it }
                startForegroundIfNeeded("Menyiapkan kamera…")
                startStreamInternal()
            }
            ACTION_STOP -> stopStreamInternal()
        }
        return START_STICKY
    }

    // ========= API untuk Activity =========

    /** Pasang SurfaceView untuk preview hanya kalau BELUM streaming. */
    fun attachPreview(view: SurfaceView) {
        if (rtmpCamera == null) {
            rtmpCamera = RtmpCamera2(view, this).apply { setReTries(10) }
        } else {
            if (isStreaming) {
                Log.w("BodyCamService", "Sedang streaming: preview tidak di-attach agar stream tidak putus.")
                return
            } else {
                runCatching { if (rtmpCamera?.isOnPreview == true) rtmpCamera?.stopPreview() }
                rtmpCamera = RtmpCamera2(view, this).apply { setReTries(10) }
            }
        }
        if (rtmpCamera?.isOnPreview != true && !isStreaming) {
            runCatching { rtmpCamera?.startPreview(CameraHelper.Facing.BACK) }
        }
    }

    /** Lepas preview; stream tetap lanjut. */
    fun detachPreview() {
        runCatching { if (rtmpCamera?.isOnPreview == true) rtmpCamera?.stopPreview() }
    }

    fun switchCamera() {
        runCatching { rtmpCamera?.switchCamera() }
    }

    fun startStream() {
        val i = Intent(this, BodyCamService::class.java).apply { action = ACTION_START }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    fun stopStream() {
        val i = Intent(this, BodyCamService::class.java).apply { action = ACTION_STOP }
        startService(i)
    }

    fun isStreamingNow(): Boolean = isStreaming

    // ========= Internal logic =========

    private fun ensureCameraForHeadless() {
        if (rtmpCamera == null) {
            val dummy = SurfaceView(this)
            dummy.holder.setFormat(PixelFormat.TRANSLUCENT)
            rtmpCamera = RtmpCamera2(dummy, this).apply { setReTries(10) }
        }
    }

    private fun startStreamInternal() {
        ensureCameraForHeadless()
        if (rtmpCamera?.isStreaming == true) return

        val width = 480
        val height = 320
        val fps = 15
        val gopSec = 2

        val audioOk = try {
            (rtmpCamera?.prepareAudio(64 * 1024, 32000, true) == true)
        } catch (_: Throwable) { false }

        val videoOk = try {
            (rtmpCamera?.prepareVideo(width, height, fps, currentBitrate, gopSec, 0) == true)
        } catch (_: Throwable) {
            (rtmpCamera?.prepareVideo(width, height, fps, currentBitrate, 0) == true)
        }

        if (!(audioOk && videoOk)) {
            updateNotif("Gagal inisialisasi encoder")
            Log.e("BodyCamService", "prepare audio=$audioOk video=$videoOk")
            sendStreamState(false)
            return
        }

        val serial = DeviceIdProvider.deviceSerialForTelemetry(this).ifBlank { "unknown" }
        val url = "$rtmpBase/$serial"

        updateNotif("Streaming ke $serial …")
        Log.i("BodyCamService", "Start stream: $url")
        rtmpCamera?.startStream(url)
    }

    private fun stopStreamInternal() {
        runCatching {
            if (rtmpCamera?.isStreaming == true) rtmpCamera?.stopStream()
            if (rtmpCamera?.isOnPreview == true) rtmpCamera?.stopPreview()
        }
        isStreaming = false

        // Hapus notifikasi & keluar dari foreground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // ✅ yang benar:
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        sendStreamState(false)
        stopSelf()
    }

    // ========= Foreground Notification =========

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "BodyCam", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Streaming BodyCam" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun baseBuilder(text: String): NotificationCompat.Builder {
        val openIntent = Intent(this, BodyCamActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_kodamjaya)
            .setContentTitle("BodyCam aktif")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    private fun startForegroundIfNeeded(text: String) {
        val notif = baseBuilder(text).build()
        startForeground(NOTIF_ID, notif)
    }

    private fun updateNotif(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, baseBuilder(text).build())
    }

    // ========= RTMP Callbacks =========

    override fun onConnectionStartedRtmp(rtmpUrl: String) { /* no-op */ }

    override fun onConnectionSuccessRtmp() {
        isStreaming = true
        rtmpCamera?.setVideoBitrateOnFly(currentBitrate)
        updateNotif("Terhubung • ${currentBitrate / 1000} kbps")
        sendStreamState(true)
    }

    override fun onNewBitrateRtmp(bitrate: Long) {
        // optional: monitor bitrate
    }

    override fun onConnectionFailedRtmp(reason: String) {
        isStreaming = false
        if (reason.contains("pipe", true) || reason.contains("write", true) || reason.contains("timeout", true)) {
            currentBitrate = (currentBitrate * 0.8).toInt().coerceAtLeast(200_000)
        }
        updateNotif("Retry… ${currentBitrate / 1000} kbps")
        if (rtmpCamera?.reTry(3000, reason, null) != true) {
            updateNotif("Gagal koneksi: $reason")
            sendStreamState(false)
        }
    }

    override fun onDisconnectRtmp() {
        isStreaming = false
        updateNotif("Terputus")
        sendStreamState(false)
    }

    override fun onAuthErrorRtmp() {
        updateNotif("Auth error")
        // status belum berubah; biarkan sesuai isStreaming
    }

    override fun onAuthSuccessRtmp() {
        updateNotif("Auth OK")
    }
}

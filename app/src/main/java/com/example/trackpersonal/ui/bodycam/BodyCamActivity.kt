package com.example.trackpersonal.ui.bodycam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.example.trackpersonal.R
import com.google.android.material.appbar.MaterialToolbar
import com.pedro.rtplibrary.rtmp.RtmpCamera2
import com.pedro.rtmp.utils.ConnectCheckerRtmp

class BodyCamActivity : AppCompatActivity(), ConnectCheckerRtmp {

    // Komponen UI dari activity_body_cam.xml
    private lateinit var surfaceView: SurfaceView
    private lateinit var switchCamera: SwitchCompat
    private lateinit var switchStream: SwitchCompat

    // Variabel untuk mengelola state
    private var useFrontCamera = false
    private var isStreaming = false
    private var currentBitrate = 100_000

    // Objek inti dari library streaming
    private lateinit var rtmpCamera: RtmpCamera2

    // URL Server RTMP Anda
    private val rtmpUrl = "rtmp://147.139.161.159:11935/drone1/live"

    // Launcher untuk meminta izin (Kamera & Mikrofon)
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraOk = permissions[Manifest.permission.CAMERA] == true
        val micOk = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (cameraOk && micOk) {
            // Jika izin diberikan, langsung mulai preview kamera
            startPreviewIfNeeded()
        } else {
            Log.w("BodyCam", "Izin kamera atau mikrofon ditolak.")
            // Matikan switch jika izin ditolak, untuk konsistensi UI
            runOnUiThread { switchStream.isChecked = false }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_body_cam)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // Jaga layar tetap menyala

        // Inisialisasi Toolbar
        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        // Hubungkan variabel dengan ID di layout XML
        surfaceView = findViewById(R.id.surfaceView)
        switchCamera = findViewById(R.id.switchCamera)
        switchStream = findViewById(R.id.switchStream)

        // Inisialisasi RtmpCamera dengan SurfaceView dan context activity ini
        rtmpCamera = RtmpCamera2(surfaceView, this)
        rtmpCamera.setReTries(10) // Izinkan library mencoba koneksi ulang hingga 10x

        // **LOGIKA UNTUK SWITCH KAMERA (DEPAN/BELAKANG)**
        switchCamera.setOnCheckedChangeListener { _, isChecked ->
            useFrontCamera = isChecked
            try {
                rtmpCamera.switchCamera()
            } catch (e: Exception) {
                Log.e("BodyCam", "Gagal mengganti kamera: ${e.message}", e)
            }
        }

        // **LOGIKA UTAMA UNTUK MEMULAI/MENGHENTIKAN STREAMING**
        switchStream.setOnCheckedChangeListener { _, isEnabled ->
            if (isEnabled) {
                // Jika switch dinyalakan (true) -> mulai streaming
                if (!hasAllPermissions()) {
                    // Jika belum ada izin, minta dulu. Jangan mulai stream.
                    switchStream.isChecked = false // Kembalikan switch ke posisi OFF
                    requestPermissionsAndPreview()
                    return@setOnCheckedChangeListener
                }
                startStreaming()
            } else {
                // Jika switch dimatikan (false) -> hentikan streaming
                stopStreaming()
            }
        }
    }

    // **LOGIKA #1: MEMULAI PREVIEW SAAT APLIKASI DIBUKA**
    override fun onResume() {
        super.onResume()
        // Fungsi ini akan dipanggil setiap kali activity muncul di layar.
        // Ini memastikan kamera akan langsung aktif.
        requestPermissionsAndPreview()
    }

    // **LOGIKA PEMBERSIHAN SAAT APLIKASI DITINGGALKAN**
    override fun onPause() {
        super.onPause()
        // Jika streaming aktif saat pengguna meninggalkan aplikasi, hentikan.
        if (rtmpCamera.isStreaming) {
            stopStreaming()
            runOnUiThread { switchStream.isChecked = false }
        }
        // Hentikan juga preview untuk menghemat baterai.
        if (rtmpCamera.isOnPreview) {
            rtmpCamera.stopPreview()
        }
    }

    // --- FUNGSI-FUNGSI PEMBANTU ---

    private fun hasAllPermissions(): Boolean {
        val cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        val audioPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        return cameraPermission == PackageManager.PERMISSION_GRANTED && audioPermission == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissionsAndPreview() {
        if (!hasAllPermissions()) {
            // Jika izin belum ada, tampilkan dialog permintaan.
            permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        } else {
            // Jika izin sudah ada, langsung nyalakan kamera.
            startPreviewIfNeeded()
        }
    }

    private fun startPreviewIfNeeded() {
        if (!isFinishing && !isDestroyed && !rtmpCamera.isOnPreview) {
            val cameraFacing = if (useFrontCamera) com.pedro.encoder.input.video.CameraHelper.Facing.FRONT else com.pedro.encoder.input.video.CameraHelper.Facing.BACK
            rtmpCamera.startPreview(cameraFacing)
        }
    }

    // **LOGIKA #2: MEMULAI PROSES STREAMING**
    private fun startStreaming() {
        if (!rtmpCamera.isOnPreview) startPreviewIfNeeded()
        if (rtmpCamera.isStreaming) return // Jika sudah streaming, abaikan

        // Konfigurasi Kualitas Video dan Audio
        // Ini adalah pengaturan yang seimbang untuk jaringan seluler.
        val audioOk = rtmpCamera.prepareAudio(64 * 1024, 32000, true)
        val videoOk = rtmpCamera.prepareVideo(320, 240, 15, currentBitrate, 0)

        if (audioOk && videoOk) {
            Log.i("BodyCam", "Mencoba memulai stream ke $rtmpUrl")
            rtmpCamera.startStream(rtmpUrl)
        } else {
            Log.e("BodyCam", "Gagal mempersiapkan stream (audio=$audioOk, video=$videoOk)")
            runOnUiThread { switchStream.isChecked = false }
        }
    }

    // **LOGIKA #3: MENGHENTIKAN PROSES STREAMING**
    private fun stopStreaming() {
        if (rtmpCamera.isStreaming) {
            rtmpCamera.stopStream()
            Log.i("BodyCam", "Streaming dihentikan manual.")
        }
        isStreaming = false
    }

    // --- CALLBACKS DARI LIBRARY RTMP (UNTUK MONITORING KONEKSI) ---

    override fun onConnectionSuccessRtmp() {
        Log.i("BodyCam", "Koneksi RTMP Berhasil!")
        isStreaming = true // Set status bahwa kita sedang streaming
        // Setelah terhubung, kita bisa mulai memonitor bitrate
        rtmpCamera.setVideoBitrateOnFly(currentBitrate)
    }

    override fun onConnectionFailedRtmp(reason: String) {
        Log.e("BodyCam", "Koneksi RTMP Gagal: $reason")
        isStreaming = false

        // **INILAH BAGIAN PENTINGNYA (ADAPTIVE BITRATE)**
        // Jika error karena "Broken pipe" atau "write failed", artinya koneksi tidak kuat.
        if (reason.contains("pipe") || reason.contains("write")) {
            Log.w("BodyCam", "Koneksi tidak stabil. Menurunkan bitrate untuk percobaan berikutnya.")
            // Turunkan bitrate sebesar 20%, dengan batas minimum 100 Kbps.
            currentBitrate = (currentBitrate * 0.8).toInt().coerceAtLeast(100_000)
        }

        // Coba sambung ulang. Jika gagal total, matikan switch.
        if (!rtmpCamera.reTry(5000, reason, null)) {
            Log.e("BodyCam", "Gagal total setelah beberapa kali mencoba.")
            runOnUiThread { switchStream.isChecked = false }
        } else {
            Log.i("BodyCam", "Mencoba menyambung ulang dengan bitrate baru: ${currentBitrate / 1000} Kbps")
        }
    }

    override fun onDisconnectRtmp() {
        Log.w("BodyCam", "Koneksi RTMP Terputus.")
        isStreaming = false
        runOnUiThread { switchStream.isChecked = false }
    }

    // Callback lain yang tidak krusial untuk logika dasar
    override fun onConnectionStartedRtmp(rtmpUrl: String) {}

    override fun onNewBitrateRtmp(bitrate: Long) {
        // Callback ini dipanggil saat library mendeteksi bahwa bitrate jaringan yang efektif telah berubah.
        // Ini sangat berguna untuk debugging.
        Log.d("BodyCam", "Jaringan mampu menangani bitrate: ${bitrate / 1000} kbps")
    }

    override fun onAuthErrorRtmp() {
        Log.e("BodyCam", "RTMP Autentikasi Gagal.")
        runOnUiThread { switchStream.isChecked = false }
    }
    override fun onAuthSuccessRtmp() {}
}
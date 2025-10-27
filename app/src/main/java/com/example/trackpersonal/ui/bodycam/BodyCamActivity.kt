package com.example.trackpersonal.ui.bodycam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.trackpersonal.R
import com.google.android.material.appbar.MaterialToolbar

class BodyCamActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var switchCamera: SwitchCompat
    private lateinit var switchStream: SwitchCompat

    private var useFrontCamera = false
    private var isStreaming = false

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            // permission ditolak → pastikan switch kembali ke kondisi OFF yang aman
            switchStream.isChecked = false
            isStreaming = false
            Log.w("BodyCam", "Izin kamera ditolak")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_body_cam)

        // Toolbar back
        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        previewView = findViewById(R.id.previewView)
        switchCamera = findViewById(R.id.switchCamera)
        switchStream = findViewById(R.id.switchStream)

        // Restore state sederhana (opsional)
        savedInstanceState?.let {
            useFrontCamera = it.getBoolean("useFrontCamera", false)
            isStreaming = it.getBoolean("isStreaming", false)
            switchCamera.isChecked = useFrontCamera
            switchStream.isChecked = isStreaming
        }

        // Cek/Request permission kamera
        if (hasCameraPermission()) {
            startCamera()
        } else {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }

        // Switch kamera depan/belakang
        switchCamera.setOnCheckedChangeListener { _, isChecked ->
            useFrontCamera = isChecked
            startCamera()
        }

        // Switch streaming RTMP (stub/no-op tanpa toast)
        switchStream.setOnCheckedChangeListener { _, enable ->
            if (enable) {
                if (!hasCameraPermission()) {
                    // batalin kalau tidak ada izin kamera
                    switchStream.isChecked = false
                    isStreaming = false
                    Log.w("BodyCam", "Streaming dibatalkan: butuh izin kamera")
                    return@setOnCheckedChangeListener
                }
                startStreaming()
            } else {
                stopStreaming()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("useFrontCamera", useFrontCamera)
        outState.putBoolean("isStreaming", isStreaming)
        super.onSaveInstanceState(outState)
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            provider.unbindAll()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val selector = if (useFrontCamera)
                CameraSelector.DEFAULT_FRONT_CAMERA
            else
                CameraSelector.DEFAULT_BACK_CAMERA

            try {
                provider.bindToLifecycle(this, selector, preview)
            } catch (e: Exception) {
                Log.e("BodyCam", "Gagal start kamera: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ====== RTMP STUB (tanpa Toast) ======
    private fun startStreaming() {
        // Integrasikan RTMP lib di sini nanti.
        isStreaming = true
        Log.i("BodyCam", "Streaming ON")
    }

    private fun stopStreaming() {
        isStreaming = false
        Log.i("BodyCam", "Streaming OFF")
    }
}
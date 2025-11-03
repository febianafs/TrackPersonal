package com.example.trackpersonal.ui.bodycam

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.example.trackpersonal.R
import com.google.android.material.appbar.MaterialToolbar

class BodyCamActivity : AppCompatActivity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var switchCamera: SwitchCompat
    private lateinit var switchStream: SwitchCompat

    private var bound = false
    private var service: BodyCamService? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bound = true
            service = (binder as BodyCamService.LocalBinder).getService()
            // attach preview hanya kalau service belum streaming (lihat kebijakan di service)
            surfaceView.post { service?.attachPreview(surfaceView) }
            switchStream.isChecked = service?.isStreamingNow() == true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { p ->
        val cam = p[Manifest.permission.CAMERA] == true
        val mic = p[Manifest.permission.RECORD_AUDIO] == true
        if (cam && mic) {
            bindToService()
        } else {
            Toast.makeText(this, "Izin kamera/mikrofon ditolak", Toast.LENGTH_SHORT).show()
            switchStream.isChecked = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_body_cam)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        surfaceView = findViewById(R.id.surfaceView)
        switchCamera = findViewById(R.id.switchCamera)
        switchStream = findViewById(R.id.switchStream)

        switchCamera.setOnCheckedChangeListener { _, _ ->
            service?.switchCamera()
        }

        switchStream.setOnCheckedChangeListener { _, isOn ->
            if (isOn) {
                if (!hasAllPermissions()) {
                    switchStream.isChecked = false
                    requestPerms()
                    return@setOnCheckedChangeListener
                }
                service?.startStream() ?: run {
                    val i = Intent(this, BodyCamService::class.java).apply {
                        action = BodyCamService.ACTION_START
                    }
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                    Toast.makeText(this, "Mulai streaming…", Toast.LENGTH_SHORT).show()
                }
            } else {
                service?.stopStream()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasAllPermissions()) bindToService() else requestPerms()
    }

    override fun onPause() {
        super.onPause()
        // Lepas preview; stream tetap lanjut di service
        service?.detachPreview()
    }

    override fun onDestroy() {
        if (bound) unbindService(conn)
        super.onDestroy()
    }

    private fun hasAllPermissions(): Boolean {
        val c = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        val a = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        return c == PackageManager.PERMISSION_GRANTED && a == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPerms() {
        permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun bindToService() {
        val i = Intent(this, BodyCamService::class.java)
        bindService(i, conn, Context.BIND_AUTO_CREATE)
    }
}
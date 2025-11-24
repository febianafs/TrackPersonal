package com.example.trackpersonal.ui.bodycam

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.example.trackpersonal.R
import com.example.trackpersonal.mqtt.MqttService
import com.example.trackpersonal.utils.SecurePref
import com.google.android.material.appbar.MaterialToolbar

class BodyCamActivity : AppCompatActivity() {

    private lateinit var surfaceView: SurfaceView
//    private lateinit var switchCamera: SwitchCompat
    private lateinit var switchStream: SwitchCompat
    private lateinit var toolbar: MaterialToolbar
    private lateinit var securePref: SecurePref

    private var bound = false
    private var service: BodyCamService? = null

    // SOS hardware key (sama seperti di MainActivity)
    private val SOS_KEYCODE = 133
    private var sosActive = false
    private var toolbarBlinkAnimator: ValueAnimator? = null
    private var toolbarBaseColor: Int? = null

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

        securePref = SecurePref(this)

        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        surfaceView = findViewById(R.id.surfaceView)
//        switchCamera = findViewById(R.id.switchCamera)
        switchStream = findViewById(R.id.switchStream)

//        switchCamera.setOnCheckedChangeListener { _, _ ->
//            service?.switchCamera()
//        }

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

        // Sync awal SOS dari SecurePref (tanpa kirim MQTT)
        syncSosFromPref()
    }

    override fun onResume() {
        super.onResume()
        if (hasAllPermissions()) bindToService() else requestPerms()

        // Pastikan setiap kali masuk BodyCam, status SOS sama dengan Main
        syncSosFromPref()
    }

    override fun onPause() {
        super.onPause()
        // Lepas preview; stream tetap lanjut di service
        service?.detachPreview()
    }

    override fun onDestroy() {
        if (bound) unbindService(conn)
        toolbarBlinkAnimator?.cancel()
        super.onDestroy()
    }

    private fun hasAllPermissions(): Boolean {
        val c = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        val a = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        return c == PackageManager.PERMISSION_GRANTED && a == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPerms() {
        permLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
        )
    }

    private fun bindToService() {
        val i = Intent(this, BodyCamService::class.java)
        bindService(i, conn, Context.BIND_AUTO_CREATE)
    }

    // ===== Hardware SOS key handling (supaya bisa SOS walau di layar BodyCam) =====
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == SOS_KEYCODE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                // toggle SOS seperti di MainActivity
                if (sosActive) {
                    stopSosBlink()
                } else {
                    startSosBlink()
                }

                // simpan ke SecurePref supaya Main & BodyCam konsisten
                securePref.saveSosActive(sosActive)

                // kirim MQTT
                MqttService.sendSos(this, sosActive)

                // optional: feedback toast
                Toast.makeText(
                    this,
                    if (sosActive) "SOS ON" else "SOS OFF",
                    Toast.LENGTH_SHORT
                ).show()

                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ===== SOS UI: toolbar kedap-kedip merah =====
    private fun startSosBlink() {
        sosActive = true

        if (toolbarBaseColor == null) {
            toolbarBaseColor =
                (toolbar.background as? android.graphics.drawable.ColorDrawable)?.color
                    ?: ContextCompat.getColor(this, R.color.black)
        }
        val from = toolbarBaseColor!!
        val to = Color.parseColor("#FF2E2E")

        toolbarBlinkAnimator?.cancel()
        toolbarBlinkAnimator = ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
            duration = 400L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                val c = anim.animatedValue as Int
                toolbar.setBackgroundColor(c)
            }
            start()
        }
    }

    private fun stopSosBlink() {
        sosActive = false
        toolbarBlinkAnimator?.cancel()
        toolbarBlinkAnimator = null
        toolbarBaseColor?.let { toolbar.setBackgroundColor(it) }
    }

    // Sync dari SecurePref → hanya update UI
    private fun syncSosFromPref() {
        val saved = securePref.isSosActive()
        if (saved && !sosActive) {
            startSosBlink()
        } else if (!saved && sosActive) {
            stopSosBlink()
        }
    }
}

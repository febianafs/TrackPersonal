package com.example.trackpersonal.ui.setting

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.trackpersonal.R
import com.example.trackpersonal.mqtt.MqttService
import com.example.trackpersonal.utils.MqttInterval
import com.example.trackpersonal.utils.SecurePref
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView

class SettingActivity : AppCompatActivity() {

    private lateinit var actInterval: MaterialAutoCompleteTextView
    private lateinit var securePref: SecurePref
    private lateinit var toolbar: MaterialToolbar

    // ===== SOS hardware key & UI =====
    private val SOS_KEYCODE = 133
    private var sosActive = false
    private var toolbarBlinkAnimator: ValueAnimator? = null
    private var toolbarBaseColor: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setting)

        securePref = SecurePref(this)

        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // ===== Interval Dropdown =====
        actInterval = findViewById(R.id.actInterval)

        val labels = MqttInterval.labels()
        actInterval.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                labels
            )
        )

        val userKey = securePref.getCurrentUserKey()
        val currentSeconds =
            securePref.getMqttIntervalSecondsForUser(userKey, defaultSeconds = 10)
        actInterval.setText(MqttInterval.labelFor(currentSeconds), false)

        actInterval.setOnItemClickListener { _, _, pos, _ ->
            val seconds = MqttInterval.secondsAt(pos)
            securePref.saveMqttIntervalSecondsForUser(userKey, seconds)
            Toast.makeText(
                this,
                "Interval diset: ${MqttInterval.labelFor(seconds)}",
                Toast.LENGTH_SHORT
            ).show()

            // Kirim broadcast internal ke service
            val i = Intent(MqttService.ACTION_INTERVAL_CHANGED)
                .setPackage(packageName)
            sendBroadcast(i)
        }

        // Sync awal SOS dari SecurePref (tanpa kirim MQTT)
        syncSosFromPref()
    }

    override fun onResume() {
        super.onResume()
        // setiap masuk Setting, samakan status SOS dengan global
        syncSosFromPref()
    }

    override fun onDestroy() {
        toolbarBlinkAnimator?.cancel()
        super.onDestroy()
    }

    // ====== SOS key handling (supaya Setting juga bisa aktifin SOS) ======

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == SOS_KEYCODE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                // toggle SOS
                if (sosActive) {
                    stopSosBlink()
                } else {
                    startSosBlink()
                }

                // simpan ke SecurePref supaya Activity lain bisa sync
                securePref.saveSosActive(sosActive)

                // kirim ke MQTT
                MqttService.sendSos(this, sosActive)

                // feedback kecil
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

    // Sync dari SecurePref → hanya update UI (tanpa kirim MQTT lagi)
    private fun syncSosFromPref() {
        val saved = securePref.isSosActive()
        if (saved && !sosActive) {
            startSosBlink()
        } else if (!saved && sosActive) {
            stopSosBlink()
        }
    }
}

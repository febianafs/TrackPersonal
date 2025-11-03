package com.example.trackpersonal.ui.setting

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.trackpersonal.R
import com.example.trackpersonal.mqtt.MqttService
import com.example.trackpersonal.utils.MqttInterval
import com.example.trackpersonal.utils.SecurePref
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView

class SettingActivity : AppCompatActivity() {

    private lateinit var actInterval: MaterialAutoCompleteTextView
    private lateinit var securePref: SecurePref

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setting)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        securePref = SecurePref(this)

        // ===== Interval Dropdown =====
        actInterval = findViewById(R.id.actInterval)

        val labels = MqttInterval.labels()
        actInterval.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))

        val userKey = securePref.getCurrentUserKey()
        val currentSeconds = securePref.getMqttIntervalSecondsForUser(userKey, defaultSeconds = 10)
        actInterval.setText(MqttInterval.labelFor(currentSeconds), false)

        actInterval.setOnItemClickListener { _, _, pos, _ ->
            val seconds = MqttInterval.secondsAt(pos)
            securePref.saveMqttIntervalSecondsForUser(userKey, seconds)
            Toast.makeText(this, "Interval diset: ${MqttInterval.labelFor(seconds)}", Toast.LENGTH_SHORT).show()

            // Kirim broadcast internal ke service
            val i = Intent(MqttService.ACTION_INTERVAL_CHANGED)
                .setPackage(packageName)
            sendBroadcast(i)
        }
    }
}

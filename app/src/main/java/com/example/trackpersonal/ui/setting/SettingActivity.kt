package com.example.trackpersonal.ui.setting

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import com.example.trackpersonal.R

class SettingActivity : AppCompatActivity() {

    private lateinit var switchMode: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setting)

        // Toolbar: back + title center sudah di XML
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        switchMode = findViewById(R.id.switchMode)

        // Pakai SharedPreferences terpisah untuk UI (bukan SecurePref)
        val sp = getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
        val savedMode = sp.getInt(
            "night_mode",
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        )

        // Terapkan mode tersimpan di startup activity (lokal + global)
        AppCompatDelegate.setDefaultNightMode(savedMode)
        delegate.localNightMode = savedMode

        // Sinkronkan switch
        switchMode.isChecked = when (savedMode) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO  -> false
            else -> {
                // Follow system → cek kondisi saat ini
                val isNight = (resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
                isNight
            }
        }

        // Toggle & simpan preferensi
        switchMode.setOnCheckedChangeListener { _, isChecked ->
            val newMode = if (isChecked) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO

            // Simpan & terapkan
            sp.edit().putInt("night_mode", newMode).apply()
            AppCompatDelegate.setDefaultNightMode(newMode)
            delegate.localNightMode = newMode

            // Refresh tampilan activity ini; bebas pakai salah satu:
            // delegate.applyDayNight()  // jika available pada versi support-mu
            recreate()
        }
    }
}
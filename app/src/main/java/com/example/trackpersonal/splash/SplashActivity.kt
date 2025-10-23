package com.example.trackpersonal.splash

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.trackpersonal.R
import com.example.trackpersonal.ui.login.LoginActivity
import com.example.trackpersonal.ui.main.MainActivity
import com.example.trackpersonal.utils.SecurePref
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    private lateinit var securePref: SecurePref

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        securePref = SecurePref(this)

        lifecycleScope.launch {
            delay(1300) // 1.3 detik

            val token = securePref.getToken()

            if (!token.isNullOrEmpty()) {
                // Sudah login, langsung ke MainActivity
                startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            } else {
                // Belum login, ke LoginActivity
                startActivity(Intent(this@SplashActivity, LoginActivity::class.java))
            }

            finish() // SplashActivity selesai, dihapus dari back stack
        }
    }
}

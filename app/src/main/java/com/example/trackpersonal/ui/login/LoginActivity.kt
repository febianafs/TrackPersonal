package com.example.trackpersonal.ui.login

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Patterns
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.trackpersonal.R
import com.example.trackpersonal.databinding.ActivityLoginBinding
import com.example.trackpersonal.ui.main.MainActivity
import com.example.trackpersonal.utils.Resource
import com.example.trackpersonal.utils.SecurePref
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var securePref: SecurePref
    private lateinit var viewModel: LoginViewModel

    private var originalBtnBackground = null as android.graphics.drawable.Drawable?
    private var originalBtnTextColor: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        securePref = SecurePref(this)

        // Simpan Android ID dari device (sekali saja)
        if (securePref.getAndroidId().isNullOrBlank()) {
            val ssaid = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: ""
            if (ssaid.isNotEmpty()) securePref.saveAndroidId(ssaid)
        }

        // Inisialisasi ViewModel (pakai Factory milikmu)
        val factory = LoginViewModelFactory(securePref)
        viewModel = ViewModelProvider(this, factory)[LoginViewModel::class.java]

        // Auto-skip jika sudah login
        val savedToken = securePref.getToken()
        if (!savedToken.isNullOrEmpty()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        // Simpan style tombol awal
        originalBtnBackground = binding.btnLogin.background
        originalBtnTextColor = binding.btnLogin.currentTextColor

        // IME action "Done" di password → langsung coba login
        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptLogin()
                true
            } else false
        }
        binding.btnLogin.setOnClickListener { attemptLogin() }

        // Observe hasil login
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.loginResult.collect { result ->
                    when (result) {
                        is Resource.Loading -> setLoading(true)
                        is Resource.Success -> {
                            setLoading(false)
                            val ok = result.data?.persistTo(securePref) == true
                            if (!ok) {
                                Toast.makeText(
                                    this@LoginActivity,
                                    "Data login tidak valid dari server",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@collect
                            }
                            startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                            finish()
                        }
                        is Resource.Error -> {
                            setLoading(false)
                            val msg = (result.message ?: "").lowercase()
                            // Pesan khusus jika kredensial salah
                            if (msg.contains("401")
                                || msg.contains("unauthorized")
                                || msg.contains("invalid")
                                || (msg.contains("email") && msg.contains("password"))
                            ) {
                                Toast.makeText(this@LoginActivity, "Email atau password salah", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@LoginActivity, result.message ?: "Login gagal", Toast.LENGTH_SHORT).show()
                            }
                        }
                        is Resource.Idle -> setLoading(false)
                    }
                }
            }
        }
    }

    private fun attemptLogin() {
        val email = binding.etEmail.text?.toString()?.trim().orEmpty()
        val password = binding.etPassword.text?.toString()?.trim().orEmpty()

        // Validasi dasar
        if (email.isEmpty()) {
            binding.etEmail.error = "Email tidak boleh kosong"
            binding.etEmail.requestFocus()
            return
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.etEmail.error = "Format email tidak valid"
            binding.etEmail.requestFocus()
            return
        }
        if (password.isEmpty()) {
            binding.etPassword.error = "Password tidak boleh kosong"
            binding.etPassword.requestFocus()
            return
        }

        // Cek koneksi internet
        if (!isInternetAvailable()) {
            Toast.makeText(this, "Tidak ada koneksi internet", Toast.LENGTH_SHORT).show()
            return
        }

        setLoading(true)
        viewModel.login(email, password)
    }

    private fun setLoading(loading: Boolean) {
        binding.btnLogin.isEnabled = !loading
        binding.btnLogin.text = if (loading) "LOADING…" else "LOGIN"

        // Feedback visual: ubah warna tombol saat loading
        if (loading) {
            val pressedColor = ContextCompat.getColor(this, R.color.deep_blue_700)
            val textColor = ContextCompat.getColor(this, android.R.color.white)
            binding.btnLogin.setBackgroundColor(pressedColor)
            binding.btnLogin.setTextColor(textColor)
        } else {
            originalBtnBackground?.let { binding.btnLogin.background = it }
            originalBtnTextColor?.let { binding.btnLogin.setTextColor(it) }
        }
    }

    // Cek koneksi internet
    private fun isInternetAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val nw = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(nw) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)
        } else {
            @Suppress("DEPRECATION")
            val ni = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            ni != null && ni.isConnected
        }
    }
}

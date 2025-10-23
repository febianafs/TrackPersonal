package com.example.trackpersonal.ui.about

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.trackpersonal.R
import com.example.trackpersonal.data.repository.AboutRepository
import com.example.trackpersonal.databinding.ActivityAboutBinding
import com.example.trackpersonal.utils.Resource
import com.example.trackpersonal.utils.SecurePref
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding
    private lateinit var securePref: SecurePref
    private lateinit var viewModel: AboutViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // init pref (dibutuhkan oleh ViewModelFactory)
        securePref = SecurePref(this)

        // Toolbar: pakai title custom (tvTitle di XML) + back
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.navigationIcon?.setTint(getColor(R.color.blue))
        binding.toolbar.setNavigationOnClickListener { finish() }
        // (opsional) ubah judul runtime:
        // binding.tvTitle.text = "ABOUT"

        // KECILKAN GAP: set padding top konten jadi 8dp (override padding dari XML)
        shrinkContentTopPadding(8)

        // ViewModel + repo
        val factory = AboutViewModelFactory(AboutRepository(securePref))
        viewModel = ViewModelProvider(this, factory)[AboutViewModel::class.java]

        // tampilkan cache dulu
        showCachedData()

        // fetch server jika sudah login
        val token = securePref.getToken()
        if (!token.isNullOrBlank()) {
            viewModel.fetchAboutUs()
        } else {
            Toast.makeText(this, "Silakan login terlebih dahulu.", Toast.LENGTH_SHORT).show()
        }

        // observe state
        lifecycleScope.launch {
            viewModel.aboutState.collectLatest { state ->
                when (state) {
                    is Resource.Loading -> Unit
                    is Resource.Success -> {
                        val d = state.data?.data
                        if (d != null) {
                            securePref.saveAbout(d)
                            updateUI(d.content, d.version, d.dev)
                        }
                    }
                    is Resource.Error -> {
                        Toast.makeText(
                            this@AboutActivity,
                            state.message ?: "Gagal memuat data",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    is Resource.Idle -> Unit
                }
            }
        }
    }

    private fun showCachedData() {
        val cachedContent = securePref.getAboutContent()
        val cachedVersion = securePref.getAboutVersion()
        val cachedDev = securePref.getAboutDev()
        updateUI(cachedContent, cachedVersion, cachedDev)
    }

    private fun updateUI(content: String?, version: String?, dev: String?) {
        binding.tvAppDesc.text = (content ?: "—").replace("\r\n", "\n")
        binding.tvVersionValue.text = version ?: "—"
        binding.tvDevValue.text = dev ?: "—"
        // Tidak ada lagi pengaturan logo/gambar di halaman About.
    }

    // KECILKAN padding top konten di dalam NestedScrollView (tanpa perlu ubah XML)
    private fun shrinkContentTopPadding(topDp: Int) {
        val content = binding.scroll.getChildAt(0) as? ViewGroup ?: return
        content.setPadding(
            content.paddingLeft,
            topDp.dp(),
            content.paddingRight,
            content.paddingBottom
        )
    }

    // utils
    private fun Int.dp(): Int =
        (this * resources.displayMetrics.density).toInt()
}

package com.example.trackpersonal.ui.main

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.example.trackpersonal.R
import com.example.trackpersonal.battery.BatteryState
import com.example.trackpersonal.data.repository.AuthRepository
import com.example.trackpersonal.databinding.ActivityMainBinding
import com.example.trackpersonal.heart.HeartRateState
import com.example.trackpersonal.heart.HeartRateViewModel
import com.example.trackpersonal.mqtt.LatestLocationStore
import com.example.trackpersonal.mqtt.MqttService
import com.example.trackpersonal.ui.about.AboutActivity
import com.example.trackpersonal.ui.login.LoginActivity
import com.example.trackpersonal.utils.Resource
import com.example.trackpersonal.utils.SecurePref
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var securePref: SecurePref

    // ViewModel header/profil
    private val viewModel: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return MainViewModel(SecurePref(this@MainActivity)) as T
            }
        }
    }

    // ViewModel lokasi
    private val locationVM: LocationViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return LocationViewModel(applicationContext) as T
            }
        }
    }

    // ViewModel baterai
    private val batteryVM: BatteryViewModel by viewModels {
        ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    }

    // ViewModel heart rate (BLE)
    private val heartRateVM: HeartRateViewModel by viewModels {
        ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    }

    // OSM map objects
    private lateinit var map: MapView
    private var myMarker: Marker? = null
    private var accuracyCircle: Polygon? = null
    private var hasCenteredOnce = false

    // Simpan last fix & mode follow
    private var lastFix: GeoPoint? = null
    private var followMyLocation = false

    // ===== SOS (hardware key) =====
    private val SOS_KEYCODE = 133                // keycode dari Logcat perangkat
    private var sosActive = false
    private var toolbarBlinkAnimator: ValueAnimator? = null
    private val sosUiHandler = Handler(Looper.getMainLooper())
    private var markerBlinking = false
    private var markerBright = true
    private var toolbarBaseColor: Int? = null

    // Launcher permission lokasi
    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarse = result[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fine || coarse) {
            startLocationUpdates()
        } else {
            Toast.makeText(this, "Izin lokasi ditolak", Toast.LENGTH_SHORT).show()
            updateCoordinates(null, null)
        }
    }

    // Launcher permission BLE (Android 12+)
    private val blePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val grantedScan = result[Manifest.permission.BLUETOOTH_SCAN] == true
        val grantedConn = result[Manifest.permission.BLUETOOTH_CONNECT] == true
        if (grantedScan && grantedConn) {
            heartRateVM.start()
        } else {
            heartRateVM.stop()
            binding.tvHeartPersonel.text = "0 bpm"
            binding.tvHeartPersonel.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        }
    }

    // ===== Permission POST_NOTIFICATIONS (Android 13+) =====
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Izin notifikasi ditolak — tracking tetap berjalan (tanpa notifikasi)", Toast.LENGTH_SHORT).show()
        }
        // Tetap coba start service; pada beberapa vendor masih diizinkan
        MqttService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        securePref = SecurePref(this)

        // Toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        // Menu titik tiga
        binding.btnMenu.setOnClickListener { v -> showPopupMenu(v) }

        // Muat data cached untuk header
        viewModel.loadCachedLogin()

        // Observe uiState (header/profile)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.tvTitle.text = state.title

                    Glide.with(this@MainActivity)
                        .load(state.logoUrl)
                        .placeholder(R.drawable.ic_logo_koopsus)
                        .error(R.drawable.ic_logo_koopsus)
                        .into(binding.imgLogo)

                    Glide.with(this@MainActivity)
                        .load(state.avatarUrl)
                        .placeholder(R.drawable.ic_soldier)
                        .error(R.drawable.ic_soldier)
                        .into(binding.imgPerson)

                    binding.tvNamePersonel.text = state.fullName
                    binding.tvNrpPersonel.text = state.nrp
                    binding.tvRankPersonel.text = state.rank
                    binding.tvUnitPersonel.text = state.unit
                    binding.tvBattalionPersonel.text = state.battalion
                    binding.tvSquadPersonel.text = state.squad
                }
            }
        }

        // Init OSM map + FAB
        initMap()
        initMapFabControls()

        // Observe lokasi, baterai, heart rate
        observeLocation()
        batteryVM.start()
        observeBattery()
        observeHeartRate()
    }

    override fun onStart() {
        super.onStart()
        // Lokasi
        if (hasLocationPermission()) {
            startLocationUpdates()
        } else {
            requestLocationPermissions()
        }
        // Heart Rate (BLE)
        ensureBlePermissionsAndStartHR()

        // ===== Start MQTT Foreground Service (penting) =====
        ensureStartMqttService()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        // Stop saat tidak di foreground
        locationVM.stop()
        heartRateVM.stop()
        if (sosActive) stopSosBlink()
    }

    private fun showPopupMenu(anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        MenuInflater(this).inflate(R.menu.main_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_about -> {
                    startActivity(Intent(this, AboutActivity::class.java))
                    true
                }
                R.id.action_logout -> {
                    performLogout()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    // ====== Lokasi ======

    private fun observeLocation() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                locationVM.state.collect { s ->
                    val lat = s.latitude
                    val lon = s.longitude

                    // FEED lokasi ke store agar MQTT Publisher bisa akses
                    if (lat != null && lon != null) {
                        val l = Location("fused").apply {
                            latitude = lat
                            longitude = lon
                            accuracy = s.accuracyMeters ?: 0f
                            time = System.currentTimeMillis()
                        }
                        LatestLocationStore.setLastLocation(l)
                    }

                    // Update label koordinat
                    val text = if (lat == null || lon == null)
                        "Lat: — | Long: —"
                    else
                        String.format(Locale.US, "Lat: %.6f | Long: %.6f", lat, lon)
                    binding.tvCoordinates.text = text

                    if (lat != null && lon != null) {
                        updateMapLocation(lat, lon, s.accuracyMeters)
                    }
                }
            }
        }
    }

    private fun startLocationUpdates() {
        locationVM.start(highAccuracy = true, intervalMillis = 2000L, minDistanceMeters = 1f)
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun requestLocationPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Jika butuh background, bisa tambahkan ACCESS_BACKGROUND_LOCATION
        }
        locationPermLauncher.launch(perms.toTypedArray())
    }

    private fun updateCoordinates(lat: Double?, lon: Double?) {
        val text = if (lat == null || lon == null)
            "Lat: — | Long: —"
        else
            String.format(Locale.US, "Lat: %.6f | Long: %.6f", lat, lon)
        binding.tvCoordinates.text = text
    }

    fun onLocationChanged(location: Location) {
        updateCoordinates(location.latitude, location.longitude)
    }

    // ===== OSM MAP =====

    private fun initMap() {
        Configuration.getInstance().load(
            applicationContext,
            PreferenceManager.getDefaultSharedPreferences(applicationContext)
        )
        Configuration.getInstance().userAgentValue = packageName

        map = binding.mapView
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.minZoomLevel = 3.0
        map.maxZoomLevel = 20.0

        val start = GeoPoint(-6.175392, 106.827153)
        map.controller.setZoom(17.0)
        map.controller.setCenter(start)

        myMarker = Marker(map).apply {
            position = start
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.ic_menu_mylocation)
            title = "Current Position"
        }
        map.overlays.add(myMarker)

        accuracyCircle = Polygon(map).apply {
            outlinePaint.strokeWidth = 2f
            outlinePaint.color = ContextCompat.getColor(this@MainActivity, R.color.primary_cyan)
            fillPaint.color = ContextCompat.getColor(this@MainActivity, R.color.primary_cyan)
            fillPaint.alpha = 40
            isVisible = false
        }
        map.overlays.add(accuracyCircle)

        map.setOnTouchListener { _, event ->
            if (event?.action == MotionEvent.ACTION_DOWN || event?.action == MotionEvent.ACTION_MOVE) {
                // user menggeser peta -> matikan follow manual (kecuali saat SOS)
                if (!sosActive) followMyLocation = false
            }
            false
        }
    }

    private fun initMapFabControls() {
        binding.fabZoomIn.setOnClickListener { map.controller.zoomIn() }
        binding.fabZoomOut.setOnClickListener { map.controller.zoomOut() }
        binding.fabMyLocation.setOnClickListener {
            if (!hasLocationPermission()) {
                requestLocationPermissions()
                return@setOnClickListener
            }
            val target = lastFix
            if (target != null) {
                val currentZoom = map.zoomLevelDouble
                if (currentZoom < 17.0) map.controller.setZoom(17.0)
                followMyLocation = true
                map.controller.animateTo(target)
            } else {
                Toast.makeText(this, "Lokasi belum tersedia", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ===== Animasi marker smooth + follow opsional =====

    private fun animateMarkerTo(
        target: GeoPoint,
        durationMs: Long = 700L,
        alsoFollow: Boolean = (followMyLocation || sosActive)
    ) {
        val start = myMarker?.position ?: target
        if (myMarker == null) return

        val startLat = start.latitude
        val startLon = start.longitude
        val dLat = target.latitude - startLat
        val dLon = target.longitude - startLon

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            addUpdateListener { va ->
                val t = (va.animatedValue as Float)
                val cur = GeoPoint(startLat + dLat * t, startLon + dLon * t)

                myMarker?.apply {
                    position = cur
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }

                if (alsoFollow) {
                    map.controller.animateTo(cur)
                }

                map.invalidate()
            }
        }
        animator.start()
    }

    private fun updateMapLocation(lat: Double, lon: Double, accuracyMeters: Float?) {
        val point = GeoPoint(lat, lon)
        val firstFix = (lastFix == null)
        lastFix = point

        if (myMarker == null) {
            // inisialisasi pertama (tanpa animasi)
            myMarker = Marker(map).apply {
                position = point
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.ic_menu_mylocation)
                title = "Current Position"
            }
            map.overlays.add(myMarker)
        } else {
            // gerakkan marker smooth
            animateMarkerTo(point)
        }

        // update akurasi
        accuracyCircle?.apply {
            val radius = (accuracyMeters ?: 0f).toDouble()
            isVisible = radius > 0
            if (isVisible) {
                points = Polygon.pointsAsCircle(point, radius)
            }
        }

        // pusatkan pertama kali saja
        if (firstFix) {
            hasCenteredOnce = true
            map.controller.animateTo(point)
        }

        map.invalidate()
    }

    // ====== BATERAI ======

    private fun observeBattery() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                batteryVM.state.collect { renderBattery(it) }
            }
        }
    }

    private fun renderBattery(state: BatteryState) {
        binding.tvBatteryPersonel.text = "${state.levelPercent} %"
        val color = when {
            state.levelPercent >= 60 -> ContextCompat.getColor(this, android.R.color.holo_green_light)
            state.levelPercent >= 20 -> ContextCompat.getColor(this, android.R.color.holo_orange_light)
            else -> ContextCompat.getColor(this, android.R.color.holo_red_light)
        }
        binding.imgBattery.imageTintList = ColorStateList.valueOf(color)
    }

    // ====== HEART RATE (BLE) ======

    private fun ensureBlePermissionsAndStartHR() {
        if (Build.VERSION.SDK_INT >= 31) {
            val needScan = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
            val needConn = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            if (needScan || needConn) {
                blePermLauncher.launch(arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                ))
            } else {
                heartRateVM.start()
            }
        } else {
            // Android 11 ke bawah tidak perlu runtime permission BLE
            heartRateVM.start()
        }
    }

    private fun observeHeartRate() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                heartRateVM.state.collect { renderHeart(it) }
            }
        }
    }

    private fun renderHeart(s: HeartRateState) {
        val text = "${s.bpm} bpm"
        binding.tvHeartPersonel.text = text
        val color = if (s.isWorn) android.R.color.white else android.R.color.darker_gray
        binding.tvHeartPersonel.setTextColor(ContextCompat.getColor(this, color))
    }

    // ===== SOS: tangkap tombol hardware & animasi =====

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == SOS_KEYCODE) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                toggleSosMode()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun toggleSosMode() {
        if (sosActive) stopSosBlink() else startSosBlink()
        // kirim ke ForegroundService agar publish MQTT retained (QoS2)
        MqttService.sendSos(this, sosActive)
    }

    private fun startSosBlink() {
        sosActive = true

        // Paksa kamera follow selama SOS
        followMyLocation = true
        lastFix?.let { map.controller.animateTo(it) }

        // Simpan warna awal toolbar (sekali saja)
        if (toolbarBaseColor == null) {
            toolbarBaseColor = (binding.toolbar.background as? android.graphics.drawable.ColorDrawable)?.color
                ?: ContextCompat.getColor(this, R.color.black)
        }
        val from = toolbarBaseColor!!
        val to = Color.parseColor("#FF2E2E") // merah SOS

        toolbarBlinkAnimator?.cancel()
        toolbarBlinkAnimator = ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
            duration = 400L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                val c = anim.animatedValue as Int
                binding.toolbar.setBackgroundColor(c)
            }
            start()
        }

        // Blink marker
        markerBlinking = true
        markerBright = true
        startMarkerBlinkLoop()
    }

    private fun stopSosBlink() {
        sosActive = false

        toolbarBlinkAnimator?.cancel()
        toolbarBlinkAnimator = null
        toolbarBaseColor?.let { binding.toolbar.setBackgroundColor(it) }

        markerBlinking = false
        resetMarkerAppearance()
        map.invalidate()
    }

    private fun startMarkerBlinkLoop() {
        sosUiHandler.post(object : Runnable {
            override fun run() {
                if (!markerBlinking) return
                myMarker?.icon?.let { dr ->
                    val wrap = DrawableCompat.wrap(dr.mutate())
                    DrawableCompat.setTint(wrap, Color.RED)
                    wrap.alpha = if (markerBright) 255 else 80
                    myMarker?.icon = wrap
                }
                markerBright = !markerBright
                map.invalidate()
                sosUiHandler.postDelayed(this, 350L)
            }
        })
    }

    private fun resetMarkerAppearance() {
        myMarker?.icon?.let { dr ->
            val wrap = DrawableCompat.wrap(dr.mutate())
            DrawableCompat.setTintList(wrap, null) // hapus tint merah
            wrap.alpha = 255
            myMarker?.icon = wrap
        }
    }

    // ===== Start FGS MQTT dengan permission notifikasi (Android 13+) =====
    private fun ensureStartMqttService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        MqttService.start(this)
    }

    // ===== Logout =====
    private fun performLogout() {
        lifecycleScope.launch {
            val repository = AuthRepository(securePref)
            when (val result = repository.logout()) {
                is Resource.Success -> {
                    Toast.makeText(
                        this@MainActivity,
                        result.data?.message ?: "Logout berhasil",
                        Toast.LENGTH_SHORT
                    ).show()
                    securePref.clear()
                    val intent = Intent(this@MainActivity, LoginActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }
                is Resource.Error -> {
                    Toast.makeText(
                        this@MainActivity,
                        result.message ?: "Gagal logout",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                else -> Unit
            }
        }
    }
}

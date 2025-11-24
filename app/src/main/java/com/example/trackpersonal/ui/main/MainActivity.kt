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
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import com.example.trackpersonal.ui.setting.SettingActivity
import org.osmdroid.views.CustomZoomButtonsController
import com.example.trackpersonal.utils.MqttInterval
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var securePref: SecurePref
    // buat nyimpen alamat garmin terakhir yang nyangkut di poc
    private var lastHrDeviceShown: String? = null
    private var lastHrConnected: Boolean = false

    private val viewModel: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return MainViewModel(SecurePref(this@MainActivity)) as T
            }
        }
    }

    private val locationVM: LocationViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return LocationViewModel(applicationContext) as T
            }
        }
    }

    private val batteryVM: BatteryViewModel by viewModels {
        ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    }

    // heart rate
    private val heartRateVM: HeartRateViewModel by viewModels {
        ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    }

    // OSM
    private lateinit var map: MapView
    private var myMarker: Marker? = null
    private var accuracyCircle: Polygon? = null
    private var lastFix: GeoPoint? = null
    private var followMyLocation = false
    private var hasCenteredOnce = false

    // SOS visual
    private val SOS_KEYCODE = 133
    private var sosActive = false
    private var toolbarBlinkAnimator: ValueAnimator? = null
    private val sosUiHandler = Handler(Looper.getMainLooper())
    private var markerBlinking = false
    private var markerBright = true
    private var toolbarBaseColor: Int? = null

    // lokasi permission
    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarse = result[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fine || coarse) startLocationUpdates()
        else {
            Toast.makeText(this, "Izin lokasi ditolak", Toast.LENGTH_SHORT).show()
            updateCoordinates(null, null)
        }
    }

    // BLE permission
    private val blePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val grantedScan = result[Manifest.permission.BLUETOOTH_SCAN] == true
        val grantedConn = result[Manifest.permission.BLUETOOTH_CONNECT] == true
        if (grantedScan && grantedConn) heartRateVM.start()
        else {
            // Satu-satunya tempat heartRateVM.stop() dipanggil adalah di BLE permission gagal
            heartRateVM.stop()
            binding.tvHeartPersonel.text = "0 bpm"
            binding.tvHeartPersonel.setTextColor(
                ContextCompat.getColor(
                    this,
                    android.R.color.darker_gray
                )
            )
            // set HR 0 supaya service kirim 0,0 sampai sensor aktif lagi
            securePref.saveHeartRate(0, System.currentTimeMillis() / 1000L)
        }
    }

    // notif permission (Android 13+)
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Izin notifikasi ditolak — tracking tetap dicoba jalan",
                Toast.LENGTH_SHORT
            ).show()
        }
        MqttService.start(this) // tetap coba start
    }

    // ===== Tambahan: izin background + battery optimization =====
    private fun ensureBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                // opsional: tampilkan dialog penjelasan dulu (why) sebelum request
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 9911)
            }
        }
    }

    private fun ensureBatteryOptWhitelist() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val pkg = packageName
            if (!pm.isIgnoringBatteryOptimizations(pkg)) {
                try {
                    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    i.data = Uri.parse("package:$pkg")
                    startActivity(i)
                } catch (_: Exception) {
                    /* no-op */
                }
            }
        }
    }

    private fun ensureIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val pkg = packageName
            val ignoring = pm.isIgnoringBatteryOptimizations(pkg)
            if (!ignoring) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:$pkg")
                    startActivity(intent)
                } catch (_: Exception) {
                    try {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(intent)
                    } catch (_: Exception) {
                        /* no-op */
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        securePref = SecurePref(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.btnMenu.setOnClickListener { v -> showPopupMenu(v) }

        // Pastikan MainViewModel mengisi state.logoUrl & state.title dari API login -> data.setting.logo/title
        viewModel.loadCachedLogin()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // Title dari login setting
                    binding.tvTitle.text = state.title

                    // Logo dari login setting
                    Glide.with(this@MainActivity)
                        .load(state.logoUrl)
                        .placeholder(R.drawable.ic_logo_koopsus)
                        .error(R.drawable.ic_logo_koopsus)
                        .into(binding.imgLogo)

                    // Avatar & biodata personel
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

        // tampilkan indikator interval saat awal masuk
        refreshIntervalIndicator()

        initMap()
        initMapFabControls()

        observeLocation()
        batteryVM.start()
        observeBattery()
        observeHeartRate()

        // Sinkronkan status SOS dari SecurePref (tanpa kirim MQTT lagi)
        syncSosFromPref()
    }

    override fun onStart() {
        super.onStart()
        // lokasi
        if (hasLocationPermission()) startLocationUpdates() else requestLocationPermissions()
        // BLE
        ensureBlePermissionsAndStartHR()
        // Minta izin/whitelist dulu
        ensureBatteryOptWhitelist()
        ensureBackgroundLocationPermission()
        ensureIgnoreBatteryOptimizations()

        // Baru start ForegroundService (kirim data setiap 10 detik sekali)
        ensureStartMqttService()

        // Pastikan setiap kali balik ke Main, UI SOS sama dengan yang tersimpan
        syncSosFromPref()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()

        // tampilkan indikator interval saat awal masuk
        refreshIntervalIndicator()

        // Jaga-jaga lagi sync SOS
        syncSosFromPref()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        locationVM.stop()
        if (sosActive) stopSosBlink()
    }

    private fun showPopupMenu(anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        MenuInflater(this).inflate(R.menu.main_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_about -> {
                    startActivity(Intent(this, AboutActivity::class.java)); true
                }
                R.id.action_cam -> {
                    startActivity(Intent(this, com.example.trackpersonal.ui.bodycam.BodyCamActivity::class.java))
                    true
                }
                R.id.action_setting -> {
                    startActivity(Intent(this, SettingActivity::class.java))
                    true
                }
                R.id.action_logout -> {
                    performLogout(); true
                }
                else -> false
            }
        }
        popup.show()
    }

    // ===== lokasi (UI) =====
    private fun observeLocation() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                locationVM.state.collect { s ->
                    val lat = s.latitude
                    val lon = s.longitude
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
        // UI refresh; tracking background ditangani MqttService
        locationVM.start(
            highAccuracy = true,
            intervalMillis = 5_000L,
            minDistanceMeters = 0f
        )
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun requestLocationPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
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

        // Matikan zoom control bawaan OSM (biar nggak dobel dengan FAB di XML)
        map.setBuiltInZoomControls(false)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)

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
                requestLocationPermissions(); return@setOnClickListener
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

                myMarker?.position = cur
                myMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                if (alsoFollow) map.controller.animateTo(cur)
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
            myMarker = Marker(map).apply {
                position = point
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.ic_menu_mylocation)
                title = "Current Position"
            }
            map.overlays.add(myMarker)
        } else {
            animateMarkerTo(point)
        }

        accuracyCircle?.apply {
            val radius = (accuracyMeters ?: 0f).toDouble()
            isVisible = radius > 0
            if (isVisible) points = Polygon.pointsAsCircle(point, radius)
        }

        if (firstFix) {
            map.controller.animateTo(point)
            hasCenteredOnce = true
        }
        map.invalidate()
    }

    // ===== Battery / Heart UI =====
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
            state.levelPercent >= 60 -> ContextCompat.getColor(
                this,
                android.R.color.holo_green_light
            )
            state.levelPercent >= 20 -> ContextCompat.getColor(
                this,
                android.R.color.holo_orange_light
            )
            else -> ContextCompat.getColor(this, android.R.color.holo_red_light)
        }
        binding.imgBattery.imageTintList = ColorStateList.valueOf(color)
    }

    private fun ensureBlePermissionsAndStartHR() {
        if (Build.VERSION.SDK_INT >= 31) {
            val needScan = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
            val needConn = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
            if (needScan || needConn) {
                blePermLauncher.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                    )
                )
            } else heartRateVM.start()
        } else heartRateVM.start()
    }

    private fun observeHeartRate() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                heartRateVM.state.collect { renderHeart(it) }
            }
        }
    }

    private fun renderHeart(s: HeartRateState) {
        // DEBUG: lihat apa yang diterima UI
        android.util.Log.d("HeartUI", "renderHeart: bpm=${s.bpm}, worn=${s.isWorn}, connected=${s.connected}")

        // Detect disconnect edge → show "disconnected" toast ONCE
        if (!s.connected && lastHrConnected) {
            Toast.makeText(
                this,
                "Heart rate device disconnected",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Always remember latest connection state
        lastHrConnected = s.connected

        // Update HR text & color
        val text = "${s.bpm} bpm"
        binding.tvHeartPersonel.text = text
        val color = if (s.isWorn) android.R.color.white else android.R.color.darker_gray
        binding.tvHeartPersonel.setTextColor(ContextCompat.getColor(this, color))

        // Build key from name + address to detect new device
        val currentDeviceKey = listOfNotNull(s.deviceName, s.deviceAddress).joinToString(" @ ")

        if (s.connected && currentDeviceKey.isNotBlank() && currentDeviceKey != lastHrDeviceShown) {
            lastHrDeviceShown = currentDeviceKey

            val label = if (s.deviceName != null && s.deviceAddress != null) {
                "Heart rate connected to ${s.deviceName} (${s.deviceAddress})"
            } else if (s.deviceAddress != null) {
                "Heart rate connected to ${s.deviceAddress}"
            } else {
                "Heart rate device connected"
            }

            Toast.makeText(this, label, Toast.LENGTH_LONG).show()
        }

        if (!s.connected) {
            lastHrDeviceShown = null
        }

        // Save HR so MQTT service can read & send it
        securePref.saveHeartRate(s.bpm, System.currentTimeMillis() / 1000L)
    }

    // ===== SOS key =====
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
        if (sosActive) {
            stopSosBlink()
        } else {
            startSosBlink()
        }
        // simpan state ke SecurePref supaya BodyCamActivity bisa ikut
        securePref.saveSosActive(sosActive)
        // kirim ke MQTT
        MqttService.sendSos(this, sosActive)
    }

    private fun startSosBlink() {
        sosActive = true
        followMyLocation = true
        lastFix?.let { map.controller.animateTo(it) }

        if (toolbarBaseColor == null) {
            toolbarBaseColor =
                (binding.toolbar.background as? android.graphics.drawable.ColorDrawable)?.color
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
                binding.toolbar.setBackgroundColor(c)
            }
            start()
        }

        markerBlinking = true
        markerBright = true
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

    private fun stopSosBlink() {
        sosActive = false
        toolbarBlinkAnimator?.cancel()
        toolbarBlinkAnimator = null
        toolbarBaseColor?.let { binding.toolbar.setBackgroundColor(it) }
        markerBlinking = false
        myMarker?.icon?.let { dr ->
            val wrap = DrawableCompat.wrap(dr.mutate())
            DrawableCompat.setTintList(wrap, null)
            wrap.alpha = 255
            myMarker?.icon = wrap
        }
        map.invalidate()
    }

    // Sync SOS dari SecurePref → hanya update UI (tanpa kirim MQTT)
    private fun syncSosFromPref() {
        val saved = securePref.isSosActive()
        if (saved && !sosActive) {
            startSosBlink()
        } else if (!saved && sosActive) {
            stopSosBlink()
        }
    }

    // ===== Start ForegroundService dengan izin notif (Android 13+) =====
    private fun ensureStartMqttService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        MqttService.start(this)
    }

    // helper untuk update label "Interval: ..."
    private fun refreshIntervalIndicator() {
        val userKey = securePref.getCurrentUserKey()
        val seconds = securePref.getMqttIntervalSecondsForUser(userKey, defaultSeconds = 10)
        binding.tvInterval.text = "Interval: ${MqttInterval.labelFor(seconds)}"
    }

    private fun proceedLocalSignOut() {
        MqttService.stop(this)                // stop service
        securePref.clearButKeepIntervals()    // bersihkan data, keep interval per user
        val i = Intent(this, LoginActivity::class.java)
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(i)
        finish()
    }

    private fun performLogout() {
        lifecycleScope.launch {
            val repo = AuthRepository(securePref)
            when (val res = repo.logout()) {
                is Resource.Success -> {
                    Toast.makeText(
                        this@MainActivity,
                        res.data?.message ?: "Logout berhasil",
                        Toast.LENGTH_SHORT
                    ).show()
                    proceedLocalSignOut()
                }

                is Resource.Error -> {
                    val m = res.message?.lowercase().orEmpty()
                    if (m.contains("401") || m.contains("unauthorized") || m.contains("expired")) {
                        Toast.makeText(
                            this@MainActivity,
                            "Sesi berakhir, keluar akun.",
                            Toast.LENGTH_SHORT
                        ).show()
                        proceedLocalSignOut()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            res.message ?: "Gagal logout",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                else -> Unit
            }
        }
    }
}

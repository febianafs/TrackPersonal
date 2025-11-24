package com.example.trackpersonal.heart

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.example.trackpersonal.utils.SecurePref
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@SuppressLint("MissingPermission")
class HeartRateRepository(private val context: Context) {

    companion object {
        private const val TAG = "HeartRepo"

        val UUID_HR_SERVICE: UUID =
            UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
        val UUID_HR_MEASUREMENT: UUID =
            UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")
        val UUID_CLIENT_CHAR_CONFIG: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Tuning
        private const val STALE_TIMEOUT_MS = 4_000L          // no packets -> 0 bpm
        private const val WEAR_CONSEC_REQUIRED = 2           // butuh 2 paket "worn"
        private const val STABILIZE_MS = 500L                // abaikan 0.5s pertama
        private const val SUPPRESS_AFTER_NOT_WORN_MS = 5_000L
        private const val VAR_WINDOW_MS = 5_000L
        private const val VAR_MIN_SPREAD = 4

        // smoothing & clamp
        private const val TAU_SMOOTH_SEC = 1.0               // lebih responsif
        private const val MAX_JUMP_PER_SEC = 80.0            // loncatan HR maksimal / detik

        // jumlah paket 0 berturut-turut yang dibutuhkan sebelum UI jadi 0 bpm
        private const val ZERO_CONSEC_REQUIRED = 2

        // deteksi pemakaian ulang (rewear) berdasarkan jarak antar paket
        private const val REWEAR_MAX_INTERVAL_MS = 1_500L    // paket dianggap "cepat" < 1.5s
        private const val REWEAR_CONSEC_REQUIRED = 2         // butuh 2 paket cepat berturut-turut

        // delay sebelum auto-scan lagi setelah disconnect
        private const val RECONNECT_DELAY_MS = 3_000L

        // paksa emit minimal setiap ini ms (walau bpm & worn sama)
        private const val FORCE_EMIT_EVERY_MS = 3_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _state = MutableStateFlow(HeartRateState())
    val state: StateFlow<HeartRateState> = _state

    private val btMgr by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val btAdapter: BluetoothAdapter? get() = btMgr.adapter

    // 🔐 pref untuk lock Garmin
    private val securePref = SecurePref(context.applicationContext)

    // alamat Garmin yang dikunci (MAC). null = belum ada binding → pakai jam pertama yang connect
    private var lockedAddress: String? = securePref.getHrDeviceAddress()

    private var gatt: BluetoothGatt? = null
    private var scanJob: Job? = null
    private var staleJob: Job? = null

    // Gates / flags
    private var notificationsReady = false
    private var consecWearPackets = 0
    private var suppressUntilMillis = 0L
    private var notifyEnabledAt = 0L

    // De-dup emisi ke UI
    private var lastEmittedBpm = -1
    private var lastEmittedWorn = false
    private var lastEmitMs: Long = 0L

    // Variance window
    private data class Sample(val t: Long, val bpm: Int, val rrCount: Int)
    private val samples = ArrayDeque<Sample>()
    private val samplesLock = Any()

    // smoothing
    private var hrFiltered: Double? = null
    private var lastUpdateMs: Long = 0L

    // flag “notWornStable” agar kalau sudah 0 stabil tidak kedip ke HR lama
    private var notWornStable = false

    // counter paket 0 (kalau mau dipakai nanti)
    private var consecZeroPackets = 0

    // deteksi pemakaian ulang (setelah "not worn stable")
    private var lastPacketMs: Long = 0L
    private var reWearConsec: Int = 0

    // menandai apakah stop() dipanggil user (bukan disconnect karena jarak)
    private var userStopped: Boolean = false

    // reconnect + notif watchdog
    private var reconnectAttempt = 0
    private var lastHrEventMs = 0L
    private var notifEnableAttempts = 0

    // receiver flags
    private var adapterRcvrRegistered = false
    private var bondRcvrRegistered = false

    private fun resetGates() {
        notificationsReady = false
        consecWearPackets = 0
        suppressUntilMillis = 0L
        notifyEnabledAt = 0L
        lastEmittedBpm = -1
        lastEmittedWorn = false
        lastEmitMs = 0L
        samples.clear()
        hrFiltered = null
        lastUpdateMs = 0L
        consecZeroPackets = 0
        notWornStable = false
        lastPacketMs = 0L
        reWearConsec = 0
        lastHrEventMs = 0L
        notifEnableAttempts = 0
    }

    /** ================= Public API ================= */

    @SuppressLint("MissingPermission")
    fun start() {
        Log.d(TAG, "start() called. lockedAddress = $lockedAddress")
        ensureReceiversRegistered()

        if (!hasBlePermissions()) {
            Log.w(TAG, "start() aborted: no BLE permission")
            _state.tryEmit(HeartRateState(connected = false, bpm = 0, isWorn = false))
            return
        }
        userStopped = false                       // penting untuk auto-reconnect
        if (gatt != null || scanJob != null) {
            Log.d(TAG, "start() ignored: already running (gatt=$gatt, scanJob=$scanJob)")
            return
        }
        scanJob = scope.launch { safeStartScan() }
    }

    fun stop() {
        Log.d(TAG, "stop() called. User stopped = true")
        userStopped = true                        // jangan auto-reconnect setelah stop manual
        scanJob?.cancel(); scanJob = null
        staleJob?.cancel(); staleJob = null
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: SecurityException) {
        }
        gatt = null
        resetGates()
        _state.tryEmit(HeartRateState(connected = false, bpm = 0, isWorn = false))
    }

    /** ================= Permissions ================= */

    private fun hasBlePermissions(): Boolean {
        val scanOk = if (Build.VERSION.SDK_INT >= 31)
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED else true
        val connectOk = if (Build.VERSION.SDK_INT >= 31)
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED else true
        val locationOk = if (Build.VERSION.SDK_INT < 31)
            (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED)
        else true
        val ok = scanOk && connectOk && locationOk
        if (!ok) Log.w(
            TAG,
            "hasBlePermissions() = false (scan=$scanOk, connect=$connectOk, loc=$locationOk)"
        )
        return ok
    }

    /** ================= Receivers ================= */

    private val adapterRcvr = object : BroadcastReceiver() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onReceive(ctx: Context, i: Intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED != i.action) return
            val state = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
            when (state) {
                BluetoothAdapter.STATE_OFF -> {
                    Log.w(TAG, "Bluetooth OFF → stop scan & reset")
                    scanJob?.cancel(); scanJob = null
                    try {
                        gatt?.disconnect()
                        gatt?.close()
                    } catch (_: Exception) {
                    }
                    gatt = null
                    resetGates()
                }

                BluetoothAdapter.STATE_ON -> {
                    Log.d(TAG, "Bluetooth ON → trigger scan if needed")
                    if (!userStopped) {
                        scheduleReconnectBackoff()
                    }
                }
            }
        }
    }

    private val bondRcvr = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, i: Intent) {
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED != i.action) return
            val dev: BluetoothDevice =
                i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
            val state =
                i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            if (gatt?.device?.address == dev.address && state == BluetoothDevice.BOND_BONDED) {
                Log.d(TAG, "Bond completed for ${dev.address}, discoverServices()")
                try {
                    gatt?.discoverServices()
                } catch (_: SecurityException) {
                }
            }
        }
    }

    private fun ensureReceiversRegistered() {
        if (!adapterRcvrRegistered) {
            context.registerReceiver(
                adapterRcvr,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            )
            adapterRcvrRegistered = true
        }
        if (!bondRcvrRegistered) {
            context.registerReceiver(
                bondRcvr,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            )
            bondRcvrRegistered = true
        }
    }

    /** ================= Scan/connect/subscribe ================= */

    @RequiresPermission(anyOf = [Manifest.permission.BLUETOOTH_SCAN])
    private suspend fun safeStartScan() = withContext(Dispatchers.IO) {
        if (!hasBlePermissions()) return@withContext
        val adapter = btAdapter ?: run {
            Log.w(TAG, "safeStartScan: btAdapter null")
            return@withContext
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            Log.w(TAG, "safeStartScan: bluetoothLeScanner null")
            return@withContext
        }

        // Filter ganda: MAC (jika ada) + HR service
        val filters = mutableListOf<ScanFilter>()

        lockedAddress?.let {
            Log.d(TAG, "safeStartScan: prefer locked device $it")
            filters += ScanFilter.Builder()
                .setDeviceAddress(it)
                .build()
        }

        // Tambah filter HR service supaya iklan HR tetap tertangkap
        filters += ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UUID_HR_SERVICE))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!hasBlePermissions()) return
                val dev = result.device
                Log.d(
                    TAG,
                    "onScanResult: found ${dev.name ?: "?"} @ ${dev.address}, rssi=${result.rssi}"
                )

                // Jika sudah locked → pastikan alamat sama
                if (lockedAddress != null && dev.address != lockedAddress) {
                    Log.d(
                        TAG,
                        "onScanResult: IGNORE device ${dev.address}, want locked=$lockedAddress"
                    )
                    return
                }

                Log.d(TAG, "onScanResult: CONNECT to ${dev.address}")
                runCatching { connect(dev) }
                runCatching { scanner.stopScan(this) }
            }

            @SuppressLint("MissingPermission")
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                if (!hasBlePermissions()) return
                Log.d(TAG, "onBatchScanResults: size=${results.size}")

                val target = results.firstOrNull { res ->
                    val dev = res.device
                    lockedAddress == null || dev.address == lockedAddress
                }?.device ?: run {
                    Log.d(
                        TAG,
                        "onBatchScanResults: no matching device for locked=$lockedAddress"
                    )
                    return
                }

                Log.d(TAG, "onBatchScanResults: CONNECT to ${target.address}")
                runCatching { connect(target) }
                runCatching { scanner.stopScan(this) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "onScanFailed: code=$errorCode")
            }
        }

        Log.d(TAG, "safeStartScan: startScan() called, filters=${filters.size}")
        @SuppressLint("MissingPermission")
        runCatching { scanner.startScan(filters, settings, cb) }
            .onFailure { Log.e(TAG, "startScan failed: ${it.message}", it) }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connect(device: BluetoothDevice) {
        if (!hasBlePermissions()) return

        // kalau sudah locked dan ini bukan device yang dikunci → abaikan
        if (lockedAddress != null && device.address != lockedAddress) {
            Log.d(TAG, "connect(): ignore ${device.address}, locked=$lockedAddress")
            return
        }

        Log.d(TAG, "connect(): connecting to ${device.address} (${device.name})")
        _state.tryEmit(
            _state.value.copy(
                connected = false,
                deviceName = device.name
            )
        )
        resetGates()
        try {
            gatt = if (Build.VERSION.SDK_INT >= 26)
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            else
                device.connectGatt(context, false, gattCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "connect(): SecurityException ${e.message}", e)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(
            g: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            Log.d(
                TAG,
                "onConnectionStateChange: device=${g.device.address}, status=$status, newState=$newState"
            )

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                reconnectAttempt = 0 // reset backoff
                val addr = g.device.address

                // 🔐 binding: kalau belum ada lockedAddress, kunci yang ini
                if (lockedAddress == null) {
                    lockedAddress = addr
                    securePref.saveHrDeviceAddress(addr)
                    Log.d(TAG, "✅ LOCKED HR device to $addr (${g.device.name})")
                } else if (lockedAddress != addr) {
                    Log.w(
                        TAG,
                        "Connected to unexpected device $addr, locked=$lockedAddress → disconnect it"
                    )
                    try {
                        if (hasBlePermissions()) g.disconnect()
                    } catch (_: SecurityException) {
                    }
                    g.close()
                    if (gatt == g) gatt = null
                    return
                }

                _state.tryEmit(
                    _state.value.copy(
                        connected = true,
                        deviceName = g.device.name,
                        deviceAddress = addr
                    )
                )
                Log.d(TAG, "Connected to ${g.device.name} @ $addr")

                resetGates()

                // Wajib bond dulu kalau belum
                val bonded = g.device.bondState == BluetoothDevice.BOND_BONDED
                if (!bonded) {
                    Log.d(TAG, "Device not bonded, calling createBond()")
                    try {
                        g.device.createBond()
                    } catch (_: SecurityException) {
                    }
                    // discoverServices akan dipanggil setelah BOND_BONDED via bondRcvr
                    return
                }

                if (hasBlePermissions()) runCatching {
                    Log.d(TAG, "discoverServices() on $addr")
                    g.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // Jangan panggil stop() di sini, pakai helper auto-reconnect
                handleDisconnected(g)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered: status=$status, device=${g.device.address}")
            if (!hasBlePermissions()) return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(
                    TAG,
                    "onServicesDiscovered: non-success status=$status → refresh cache & reconnect"
                )
                refreshGattCache(g)
                handleDisconnected(g)
                return
            }

            notifEnableAttempts = 0
            lastHrEventMs = 0L

            if (!enableHrNotifications(g)) {
                Log.w(TAG, "enableHrNotifications() failed → refresh & reconnect")
                refreshGattCache(g)
                handleDisconnected(g)
                return
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            g: BluetoothGatt,
            d: BluetoothGattDescriptor,
            status: Int
        ) {
            if (d.uuid == UUID_CLIENT_CHAR_CONFIG) {
                notificationsReady = (status == BluetoothGatt.GATT_SUCCESS)
                notifyEnabledAt = System.currentTimeMillis()
                notifEnableAttempts++
                Log.d(
                    TAG,
                    "onDescriptorWrite CCC: status=$status, notificationsReady=$notificationsReady, attempts=$notifEnableAttempts"
                )

                // Watchdog: jika setelah 2.5s belum ada event HR, coba tulis CCCD ulang (max 3x)
                scope.launch {
                    delay(2500L)
                    val age = System.currentTimeMillis() - lastHrEventMs
                    if (notificationsReady && age > 2000L) {
                        Log.w(TAG, "No HR events after enabling notif (age=${age}ms)")
                        if (notifEnableAttempts < 3) {
                            Log.w(TAG, "Retry enabling CCCD (#$notifEnableAttempts)")
                            enableHrNotifications(g)
                        } else {
                            Log.w(
                                TAG,
                                "CCCD still dead → refresh cache & reconnect"
                            )
                            refreshGattCache(g)
                            handleDisconnected(g)
                        }
                    }
                }

                restartStaleWatchdog()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic
        ) {
            if (ch.uuid != UUID_HR_MEASUREMENT || !notificationsReady) return

            val now = System.currentTimeMillis()
            lastHrEventMs = now

            // hitung jarak waktu antar paket
            val dtPacket =
                if (lastPacketMs == 0L) Long.MAX_VALUE else (now - lastPacketMs)
            lastPacketMs = now

            val p = parseHrMeasurement(ch.value)      // bpm, isWorn, contactSupported, rrCount
            val bpmRaw = p.bpm
            val wornFlag = p.isWorn
            val contactSupported = p.contactSupported
            val rrCount = p.rrCount

            Log.d(
                TAG,
                "HR packet: bpmRaw=$bpmRaw, wornFlag=$wornFlag, contactSupported=$contactSupported, rrCount=$rrCount, dtPacket=${dtPacket}ms"
            )

            // 1) Stabilization window setelah CCCD aktif (abaikan spikes awal)
            if ((now - notifyEnabledAt) < STABILIZE_MS && wornFlag) {
                Log.d(TAG, "  skip: in stabilize window")
                return
            }

            // 2) Hard-suppress setelah not worn (tahan nilai > 0 beberapa detik)
            if (now < suppressUntilMillis && wornFlag) {
                Log.d(TAG, "  skip: under suppressUntilMillis=$suppressUntilMillis")
                return
            }

            // 2b) Kalau sudah "notWornStable" dan TIDAK ada contact flag (Garmin),
//     sederhanakan: kalau ada bpmRaw > 0, anggap dipakai lagi.
            if (notWornStable && !contactSupported) {
                if (bpmRaw > 0) {
                    Log.d(TAG, "  reWear SIMPLE: bpmRaw>0 while notWornStable → unlock")
                    notWornStable = false
                    hrFiltered = null
                    reWearConsec = 0
                    // lanjut ke heuristik biasa di bawah
                } else {
                    Log.d(TAG, "  still not worn (bpmRaw<=0)")
                    restartStaleWatchdog()
                    return
                }
            }

            // 3) Heuristik variabilitas/RR untuk device tanpa contact flag
            pushSample(now, bpmRaw, rrCount)
            val lowVariance = isLowVariance(now)
            val rrAbsent = isRrAbsent(now)

            var accept = false
            var bpmFinal = 0
            var wornFinal = false

            if (contactSupported) {
                // device punya contact flag → pakai langsung
                if (wornFlag) {
                    accept = true; bpmFinal = max(0, bpmRaw); wornFinal = true
                    consecWearPackets = WEAR_CONSEC_REQUIRED
                } else {
                    accept = true; bpmFinal = 0; wornFinal = false
                    consecWearPackets = 0
                    suppressUntilMillis = now + SUPPRESS_AFTER_NOT_WORN_MS
                }
            } else {
                // device TANPA contact flag (tipikal jam Garmin)
                if (bpmRaw > 0) {
                    // HR > 0 tapi variansi kecil & tidak ada RR → kemungkinan jam dilepas
                    if (lowVariance && rrAbsent) {
                        Log.d(TAG, "  lowVariance+noRR → treat as NOT worn (bpm->0)")
                        accept = true; bpmFinal = 0; wornFinal = false
                        consecWearPackets = 0
                        suppressUntilMillis = now + SUPPRESS_AFTER_NOT_WORN_MS
                    } else {
                        consecWearPackets += 1
                        Log.d(TAG, "  wearPackets=$consecWearPackets")
                        if (consecWearPackets >= WEAR_CONSEC_REQUIRED) {
                            accept = true; bpmFinal = max(0, bpmRaw); wornFinal = true
                        }
                    }
                } else {
                    // HR mentah 0 → anggap tidak dipakai
                    Log.d(TAG, "  raw bpm=0 → NOT worn")
                    accept = true; bpmFinal = 0; wornFinal = false
                    consecWearPackets = 0
                    suppressUntilMillis = now + SUPPRESS_AFTER_NOT_WORN_MS
                }
            }

            if (accept) {
                Log.d(TAG, "  ACCEPT: bpmFinal=$bpmFinal, wornFinal=$wornFinal")
                processAndEmit(bpmFinal, wornFinal, now)
            } else {
                Log.d(TAG, "  REJECT sample")
            }

            // kalau udah pakai lagi (>= WEAR_CONSEC_REQUIRED paket), langsung izinkan update
            if (consecWearPackets >= WEAR_CONSEC_REQUIRED) {
                suppressUntilMillis = 0L
            }

            restartStaleWatchdog()
        }
    }

    /** ===== Auto-reconnect helper ===== */
    @SuppressLint("MissingPermission")
    private fun handleDisconnected(g: BluetoothGatt) {
        Log.w(TAG, "handleDisconnected: from ${g.device.address}, userStopped=$userStopped")
        staleJob?.cancel()

        _state.tryEmit(
            HeartRateState(
                connected = false,
                deviceName = g.device.name,
                bpm = 0,
                isWorn = false,
                lastUpdatedMillis = System.currentTimeMillis()
            )
        )

        // bersihkan GATT
        try {
            if (hasBlePermissions()) {
                try {
                    g.disconnect()
                } catch (_: SecurityException) {
                }
            }
            g.close()
        } catch (e: Exception) {
            Log.e(TAG, "handleDisconnected: error closing GATT ${e.message}", e)
        }

        if (gatt == g) gatt = null
        resetGates()

        // kalau bukan stop() manual → auto scan lagi (tetap pakai lockedAddress yang sama)
        if (!userStopped) {
            scheduleReconnectBackoff()
        } else {
            Log.d(TAG, "handleDisconnected: not auto-reconnecting (userStopped=true)")
            reconnectAttempt = 0
        }
    }

    private fun scheduleReconnectBackoff() {
        // exponential backoff: 1.5s, 3s, 6s, 12s, 20s (max)
        val delayMs = (1500L * (1 shl reconnectAttempt)).coerceAtMost(20_000L)
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(5)
        Log.d(TAG, "schedule reconnect in ${delayMs}ms (attempt=$reconnectAttempt)")
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(delayMs)
            safeStartScan()   // sudah @RequiresPermission di definisinya
        }
    }

    /** ===== Variance helpers ===== */
    private fun pushSample(now: Long, bpm: Int, rrCount: Int) {
        synchronized(samplesLock) {
            samples.addLast(Sample(now, bpm, rrCount))
            pruneOldLocked(now)
        }
    }

    private fun pruneOldLocked(now: Long) {
        while (samples.isNotEmpty()) {
            val first = samples.firstOrNull() ?: break
            if (now - first.t > VAR_WINDOW_MS) {
                samples.removeFirst()
            } else {
                break
            }
        }
    }

    private fun isLowVariance(now: Long): Boolean {
        synchronized(samplesLock) {
            pruneOldLocked(now)
            if (samples.size < 3) return false

            var lo = Int.MAX_VALUE
            var hi = Int.MIN_VALUE
            samples.forEach { s ->
                lo = min(lo, s.bpm)
                hi = max(hi, s.bpm)
            }
            return (hi - lo) < VAR_MIN_SPREAD
        }
    }

    private fun isRrAbsent(now: Long): Boolean {
        synchronized(samplesLock) {
            pruneOldLocked(now)
            if (samples.isEmpty()) return true
            var anyRr = false
            samples.forEach { s ->
                if (s.rrCount > 0) anyRr = true
            }
            return !anyRr
        }
    }

    /** ===== Emission & smoothing & watchdog ===== */

    private fun emitIfChanged(bpm: Int, worn: Boolean, ts: Long) {
        val now = System.currentTimeMillis()
        val same = (bpm == lastEmittedBpm && worn == lastEmittedWorn)
        val dt = now - lastEmitMs
        val recently = dt in 0..FORCE_EMIT_EVERY_MS

        if (same && recently) {
            Log.d(
                TAG,
                "emitIfChanged: SKIP (same bpm=$bpm worn=$worn, dt=${dt}ms)"
            )
            return
        }

        // update cache
        lastEmittedBpm = bpm
        lastEmittedWorn = worn
        lastEmitMs = now

        Log.d(TAG, "emitIfChanged: EMIT bpm=$bpm, worn=$worn, ts=$ts")
        _state.tryEmit(
            _state.value.copy(
                bpm = bpm,
                isWorn = worn,
                lastUpdatedMillis = ts,
                connected = true
            )
        )
    }

    private fun processAndEmit(bpm: Int, worn: Boolean, ts: Long) {
        // kalau tidak dipakai (not worn) → langsung nol dan hentikan smoothing
        if (!worn || bpm <= 0) {
            if (!notWornStable) {
                Log.d(TAG, "processAndEmit: set NOT WORN & 0 bpm")
                notWornStable = true
                hrFiltered = 0.0
                lastUpdateMs = ts
                emitIfChanged(0, false, ts)
            }
            return
        }

        // kalau mulai dipakai lagi → reset flag dan smoothing
        if (notWornStable) {
            Log.d(TAG, "processAndEmit: worn again, reset smoothing")
            notWornStable = false
            hrFiltered = null
        }

        // smoothing normal untuk data valid
        val prev = hrFiltered ?: bpm.toDouble()
        val dtMs = (ts - lastUpdateMs).coerceAtLeast(1L)
        val dtSec = dtMs.toDouble() / 1000.0
        lastUpdateMs = ts

        val alphaBase = (dtSec / (TAU_SMOOTH_SEC + dtSec)).coerceIn(0.0, 1.0)
        val maxJump = MAX_JUMP_PER_SEC * dtSec
        val limitedRaw = when {
            bpm - prev > maxJump -> prev + maxJump
            prev - bpm > maxJump -> prev - maxJump
            else -> bpm.toDouble()
        }

        val diff = kotlin.math.abs(limitedRaw - prev)
        val alpha =
            if (limitedRaw >= prev) 0.87 else if (diff > 10.0) max(alphaBase, 0.6) else alphaBase
        val filtered = prev + alpha * (limitedRaw - prev)
        hrFiltered = filtered

        val filteredInt = filtered.roundToInt().coerceIn(30, 230)
        Log.d(TAG, "processAndEmit: raw=$bpm, limited=$limitedRaw, filtered=$filteredInt")
        emitIfChanged(filteredInt, true, ts)
    }

    private fun restartStaleWatchdog() {
        // snapshot waktu paket terakhir saat watchdog dijadwalkan
        val scheduledLastPacket = lastPacketMs

        staleJob?.cancel()
        staleJob = scope.launch {
            delay(STALE_TIMEOUT_MS)

            val now = System.currentTimeMillis()

            // Kalau sejak watchdog dijadwalkan sudah ada paket baru,
            // artinya data masih mengalir → jangan paksa 0.
            if (lastPacketMs != 0L && lastPacketMs != scheduledLastPacket) {
                Log.d(
                    TAG,
                    "staleWatchdog: newer HR packet detected (lastPacketMs=${lastPacketMs}), skip"
                )
                return@launch
            }

            // Kalau memang sudah dalam status tidak dipakai, juga nggak perlu apa-apa
            if (!_state.value.isWorn) {
                Log.d(TAG, "staleWatchdog: already not worn, skip")
                return@launch
            }

            // Benar-benar tidak ada paket baru dalam window → anggap dilepas
            Log.w(TAG, "staleWatchdog: no HR packets → force 0 bpm & not worn")
            hrFiltered = 0.0
            lastEmittedBpm = 0
            lastEmittedWorn = false
            _state.emit(
                _state.value.copy(
                    bpm = 0,
                    isWorn = false,
                    lastUpdatedMillis = now
                )
            )
            notWornStable = true
        }
    }

    /** ===== GATT helpers ===== */

    private fun refreshGattCache(g: BluetoothGatt): Boolean {
        return try {
            val m = g.javaClass.getMethod("refresh")
            m.isAccessible = true
            (m.invoke(g) as Boolean).also {
                Log.d(TAG, "refreshGattCache() -> $it")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "refreshGattCache failed: ${t.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableHrNotifications(g: BluetoothGatt): Boolean {
        val svc = g.getService(UUID_HR_SERVICE) ?: run {
            Log.w(TAG, "HR service not found on ${g.device.address}")
            return false
        }
        val hrm = svc.getCharacteristic(UUID_HR_MEASUREMENT) ?: run {
            Log.w(TAG, "HR measurement char not found on ${g.device.address}")
            return false
        }

        if (!g.setCharacteristicNotification(hrm, true)) {
            Log.e(TAG, "setCharacteristicNotification() returned false")
            return false
        }

        val ccc = hrm.getDescriptor(UUID_CLIENT_CHAR_CONFIG) ?: run {
            Log.w(TAG, "CCC descriptor not found on HR measurement")
            return false
        }
        notificationsReady = false
        ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        Log.d(
            TAG,
            "writeDescriptor(CCC) to enable notifications (attempt=${notifEnableAttempts + 1})"
        )
        return g.writeDescriptor(ccc)
    }

    /** ===== Parser dengan RR count ===== */
    private data class HrParsed(
        val bpm: Int,
        val isWorn: Boolean,
        val contactSupported: Boolean,
        val rrCount: Int
    )

    private fun parseHrMeasurement(data: ByteArray): HrParsed {
        if (data.isEmpty()) return HrParsed(0, false, false, 0)
        val flags = data[0].toInt() and 0xFF
        val formatUInt16 = (flags and 0x01) != 0
        val contactSupported = (flags and 0x02) != 0
        val contactDetected = (flags and 0x04) != 0
        val energyPresent = (flags and 0x08) != 0
        val rrPresent = (flags and 0x10) != 0

        var offset = 1
        val hr = if (formatUInt16) {
            val v =
                (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2; v
        } else {
            val v = data[offset].toInt() and 0xFF
            offset += 1; v
        }

        if (energyPresent) offset += 2 // skip Energy Expended (uint16)

        var rrCount = 0
        if (rrPresent) {
            while (offset + 1 < data.size) {
                rrCount += 1
                offset += 2
            }
        }

        val worn = if (contactSupported) contactDetected else (hr > 0)
        return HrParsed(hr, worn, contactSupported, rrCount)
    }
}

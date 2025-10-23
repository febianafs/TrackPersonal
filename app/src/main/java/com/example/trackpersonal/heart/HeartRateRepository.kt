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
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
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

class HeartRateRepository(private val context: Context) {

    companion object {
        val UUID_HR_SERVICE: UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
        val UUID_HR_MEASUREMENT: UUID = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")
        val UUID_CLIENT_CHAR_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Tuning (boleh kamu adjust bila perlu)
        private const val STALE_TIMEOUT_MS = 2_000L              // no packets -> 0 bpm
        private const val WEAR_CONSEC_REQUIRED = 4               // paket "worn" beruntun
        private const val STABILIZE_MS = 1_500L                  // abaikan sesaat setelah CCCD aktif
        private const val SUPPRESS_AFTER_NOT_WORN_MS = 5_000L    // block nilai lama setelah dilepas
        private const val VAR_WINDOW_MS = 5_000L                 // jendela variabilitas
        private const val VAR_MIN_SPREAD = 4                     // spread minimal agar dianggap valid
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _state = MutableStateFlow(HeartRateState())
    val state: StateFlow<HeartRateState> = _state

    private val btMgr by lazy { context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val btAdapter: BluetoothAdapter? get() = btMgr.adapter

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

    // Variance window
    private data class Sample(val t: Long, val bpm: Int, val rrCount: Int)
    private val samples = ArrayDeque<Sample>()  // keep last ≤ VAR_WINDOW_MS

    private fun resetGates() {
        notificationsReady = false
        consecWearPackets = 0
        suppressUntilMillis = 0L
        notifyEnabledAt = 0L
        lastEmittedBpm = -1
        lastEmittedWorn = false
        samples.clear()
    }

    /** ================= Public API ================= */

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasBlePermissions()) {
            _state.tryEmit(HeartRateState(connected = false, bpm = 0, isWorn = false))
            return
        }
        if (gatt != null || scanJob != null) return
        scanJob = scope.launch { safeStartScan() }
    }

    fun stop() {
        scanJob?.cancel(); scanJob = null
        staleJob?.cancel(); staleJob = null
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: SecurityException) { }
        gatt = null
        resetGates()
        _state.tryEmit(HeartRateState(connected = false, bpm = 0, isWorn = false))
    }

    /** ================= Permissions ================= */

    private fun hasBlePermissions(): Boolean {
        val scanOk = if (Build.VERSION.SDK_INT >= 31)
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED else true
        val connectOk = if (Build.VERSION.SDK_INT >= 31)
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED else true
        val locationOk = if (Build.VERSION.SDK_INT < 31)
            (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        else true
        return scanOk && connectOk && locationOk
    }

    /** ================= Scan/connect/subscribe ================= */

    @RequiresPermission(anyOf = [Manifest.permission.BLUETOOTH_SCAN])
    private suspend fun safeStartScan() = withContext(Dispatchers.IO) {
        if (!hasBlePermissions()) return@withContext
        val adapter = btAdapter ?: return@withContext
        val scanner = adapter.bluetoothLeScanner ?: return@withContext

        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID_HR_SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        val cb = object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!hasBlePermissions()) return
                runCatching { connect(result.device) }
                runCatching { scanner.stopScan(this) }
            }
            @SuppressLint("MissingPermission")
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                if (!hasBlePermissions()) return
                results.firstOrNull()?.device?.let {
                    runCatching { connect(it) }
                    runCatching { scanner.stopScan(this) }
                }
            }
        }

        @SuppressLint("MissingPermission")
        runCatching { scanner.startScan(filters, settings, cb) }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connect(device: BluetoothDevice) {
        if (!hasBlePermissions()) return
        _state.tryEmit(_state.value.copy(connected = false, deviceName = device.name))
        resetGates()
        try {
            gatt = if (Build.VERSION.SDK_INT >= 26)
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            else
                device.connectGatt(context, false, gattCallback)
        } catch (_: SecurityException) { }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _state.tryEmit(_state.value.copy(connected = true, deviceName = g.device.name))
                resetGates()
                if (hasBlePermissions()) runCatching { g.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
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
                stop()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (!hasBlePermissions()) return
            val svc = runCatching { g.getService(UUID_HR_SERVICE) }.getOrNull() ?: return
            val hrm = runCatching { svc.getCharacteristic(UUID_HR_MEASUREMENT) }.getOrNull() ?: return

            runCatching { g.setCharacteristicNotification(hrm, true) }.onFailure { return }
            runCatching {
                val ccc = hrm.getDescriptor(UUID_CLIENT_CHAR_CONFIG) ?: return
                notificationsReady = false
                ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(ccc) // → onDescriptorWrite
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid == UUID_CLIENT_CHAR_CONFIG) {
                notificationsReady = (status == BluetoothGatt.GATT_SUCCESS)
                notifyEnabledAt = System.currentTimeMillis()
                restartStaleWatchdog()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid != UUID_HR_MEASUREMENT || !notificationsReady) return
            val now = System.currentTimeMillis()
            val p = parseHrMeasurement(ch.value)      // bpm, isWorn, contactSupported, rrCount
            val bpmRaw = p.bpm
            val wornFlag = p.isWorn
            val contactSupported = p.contactSupported
            val rrCount = p.rrCount

            // 1) Stabilization window setelah CCCD aktif (abaikan spikes awal)
            if ((now - notifyEnabledAt) < STABILIZE_MS && wornFlag) return

            // 2) Hard-suppress setelah not worn (tahan nilai > 0 beberapa detik)
            if (now < suppressUntilMillis && wornFlag) return

            // 3) Heuristik variabilitas/RR untuk device tanpa contact flag
            pushSample(now, bpmRaw, rrCount)
            val lowVariance = isLowVariance(now)
            val rrAbsent = isRrAbsent(now)

            var accept = false
            var bpmFinal = 0
            var wornFinal = false

            if (contactSupported) {
                if (wornFlag) {
                    accept = true; bpmFinal = max(0, bpmRaw); wornFinal = true
                    consecWearPackets = WEAR_CONSEC_REQUIRED
                } else {
                    accept = true; bpmFinal = 0; wornFinal = false
                    consecWearPackets = 0
                    suppressUntilMillis = now + SUPPRESS_AFTER_NOT_WORN_MS
                }
            } else {
                if (bpmRaw > 0) {
                    if (lowVariance && rrAbsent) {
                        accept = true; bpmFinal = 0; wornFinal = false
                        consecWearPackets = 0
                        suppressUntilMillis = now + SUPPRESS_AFTER_NOT_WORN_MS
                    } else {
                        consecWearPackets += 1
                        if (consecWearPackets >= WEAR_CONSEC_REQUIRED) {
                            accept = true; bpmFinal = max(0, bpmRaw); wornFinal = true
                        }
                    }
                } else {
                    accept = true; bpmFinal = 0; wornFinal = false
                    consecWearPackets = 0
                    suppressUntilMillis = now + SUPPRESS_AFTER_NOT_WORN_MS
                }
            }

            if (accept) emitIfChanged(bpmFinal, wornFinal, now)
            restartStaleWatchdog()
        }
    }

    /** ===== Variance helpers ===== */
    private fun pushSample(now: Long, bpm: Int, rrCount: Int) {
        samples.addLast(Sample(now, bpm, rrCount))
        pruneOld(now)
    }

    private fun pruneOld(now: Long) {
        while (samples.isNotEmpty() && now - samples.first().t > VAR_WINDOW_MS) {
            samples.removeFirst()
        }
    }

    private fun isLowVariance(now: Long): Boolean {
        pruneOld(now)
        if (samples.size < 3) return false
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        samples.forEach { s ->
            lo = min(lo, s.bpm)
            hi = max(hi, s.bpm)
        }
        return (hi - lo) < VAR_MIN_SPREAD
    }

    private fun isRrAbsent(now: Long): Boolean {
        pruneOld(now)
        if (samples.isEmpty()) return true
        var anyRr = false
        samples.forEach { s -> if (s.rrCount > 0) anyRr = true }
        return !anyRr
    }

    /** ===== Emission & watchdog ===== */
    private fun emitIfChanged(bpm: Int, worn: Boolean, ts: Long) {
        if (bpm == lastEmittedBpm && worn == lastEmittedWorn) return
        lastEmittedBpm = bpm
        lastEmittedWorn = worn
        _state.tryEmit(
            _state.value.copy(
                bpm = bpm,
                isWorn = worn,
                lastUpdatedMillis = ts,
                connected = true
            )
        )
    }

    private fun restartStaleWatchdog() {
        staleJob?.cancel()
        staleJob = scope.launch {
            delay(STALE_TIMEOUT_MS)
            samples.clear()
            consecWearPackets = 0
            if (lastEmittedBpm != 0 || lastEmittedWorn != false) {
                lastEmittedBpm = 0
                lastEmittedWorn = false
                _state.emit(
                    _state.value.copy(
                        bpm = 0,
                        isWorn = false,
                        lastUpdatedMillis = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    /** ===== Parser dengan RR count =====
     * Flags spec 0x2A37:
     * bit0: HR 16-bit present
     * bit1: Sensor Contact Supported
     * bit2: Sensor Contact Detected
     * bit3: Energy Expended Present (skip)
     * bit4: RR-Interval Present
     */
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
            val v = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2; v
        } else {
            val v = data[offset].toInt() and 0xFF
            offset += 1; v
        }

        if (energyPresent) offset += 2 // skip Energy Expended (uint16)

        var rrCount = 0
        if (rrPresent) {
            // RR interval adalah array uint16 LE, tiap 1/1024 detik
            while (offset + 1 < data.size) {
                rrCount += 1
                offset += 2
            }
        }

        val worn = if (contactSupported) contactDetected else (hr > 0)
        return HrParsed(hr, worn, contactSupported, rrCount)
    }
}

package com.example.trackpersonal.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class BatteryMonitor(private val appContext: Context) {

    fun observe() = callbackFlow {
        // emit nilai awal dari sticky intent
        trySend(readOnce(appContext))

        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(parseFromIntent(context, intent))
            }
        }
        appContext.registerReceiver(receiver, filter)

        awaitClose { runCatching { appContext.unregisterReceiver(receiver) } }
    }.distinctUntilChanged()

    private fun readOnce(context: Context): BatteryState {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return parseFromIntent(context, intent)
    }

    private fun parseFromIntent(context: Context, intent: Intent?): BatteryState {
        // 1) coba pakai BatteryManager (lebih langsung, kadang device OEM override)
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        var percent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (percent <= 0 || percent > 100) {
            // 2) fallback dari EXTRA_LEVEL/EXTRA_SCALE
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            percent = if (level >= 0 && scale > 0) (level * 100f / scale).toInt() else -1
        }

        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        return BatteryState(levelPercent = percent.coerceIn(0, 100), isCharging = isCharging)
    }
}

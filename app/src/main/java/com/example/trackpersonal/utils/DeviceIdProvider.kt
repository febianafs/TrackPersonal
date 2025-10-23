package com.example.trackpersonal.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

object DeviceIdProvider {

    fun deviceSerialForTelemetry(context: Context): String {
        // 1) Coba IMEI jika benar-benar memungkinkan (Android 8–9 + READ_PHONE_STATE)
        val imei = tryImeiIfAllowed(context)
        if (imei != null && imei.isNotBlank()) return imei

        // 2) Fallback yang selalu aman di Android 10+: ANDROID_ID
        val ssaid = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        ) ?: "UNKNOWN"

        return ssaid
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun tryImeiIfAllowed(context: Context): String? {
        // IMEI bisa dicoba secara realistis hanya di Android 8–9
        if (Build.VERSION.SDK_INT >= 29) return null

        val hasPerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return null

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= 26) {
                tm.imei ?: tm.meid ?: tm.deviceId
            } else {
                @Suppress("DEPRECATION") tm.deviceId
            }
        } catch (_: SecurityException) {
            null
        }
    }
}

package com.example.trackpersonal.utils

import android.content.Context
import android.provider.Settings

object DeviceInfo {
    fun androidId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: ""
}
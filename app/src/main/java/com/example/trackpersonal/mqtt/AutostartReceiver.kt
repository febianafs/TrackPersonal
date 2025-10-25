package com.example.trackpersonal.mqtt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class AutostartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Start ulang service setelah BOOT atau setelah app di-update
        val i = Intent(context, MqttService::class.java).setAction("MQTT_START")
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(i)
        } else {
            context.startService(i)
        }
    }
}

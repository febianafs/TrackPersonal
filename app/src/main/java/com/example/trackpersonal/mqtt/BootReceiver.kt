package com.example.trackpersonal.mqtt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // Kick sekali (tunda sedikit agar radio/data attach dulu)
        val oneTime = OneTimeWorkRequestBuilder<MqttKickWorker>()
            .setInitialDelay(15, TimeUnit.SECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "mqtt_kick_once",
            ExistingWorkPolicy.REPLACE,
            oneTime
        )

        // Watchdog periodik (interval minimal WorkManager = 15 menit)
        val periodic = PeriodicWorkRequestBuilder<MqttWatchdogWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "mqtt_watchdog",
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )
    }
}

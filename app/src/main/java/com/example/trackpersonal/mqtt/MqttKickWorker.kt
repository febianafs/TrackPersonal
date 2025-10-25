package com.example.trackpersonal.mqtt

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Worker sekali jalan untuk menyalakan MqttService setelah boot/app update.
 */
class MqttKickWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Idempotent: aman dipanggil berulang
        MqttService.start(applicationContext)
        return Result.success()
    }
}
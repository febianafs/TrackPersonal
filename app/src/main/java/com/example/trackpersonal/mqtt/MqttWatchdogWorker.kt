package com.example.trackpersonal.mqtt

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Worker periodik 15 menit untuk memastikan service tetap hidup
 * (jika dimatikan OEM/Doze, kita panggil start lagi).
 */
class MqttWatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Cukup start lagi; Service kamu sudah idempotent/guarded.
        MqttService.start(applicationContext)
        return Result.success()
    }
}
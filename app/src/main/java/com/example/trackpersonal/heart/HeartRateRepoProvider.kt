package com.example.trackpersonal.heart

import android.content.Context

object HeartRateRepoProvider {
    //Ini memastikan selalu 1 instance HeartRateRepository untuk seluruh app
    @Volatile
    private var instance: HeartRateRepository? = null

    fun get(context: Context): HeartRateRepository {
        val appCtx = context.applicationContext
        return instance ?: synchronized(this) {
            instance ?: HeartRateRepository(appCtx).also { instance = it }
        }
    }
}

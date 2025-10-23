package com.example.trackpersonal.heart

data class HeartRateState(
    val connected: Boolean = false,
    val deviceName: String? = null,
    val bpm: Int = 0,                 // 0 bpm = dilepas/tidak ada kontak
    val isWorn: Boolean = false,      // sensor contact
    val lastUpdatedMillis: Long = 0L
)

package com.example.trackpersonal.battery

data class BatteryState(
    val levelPercent: Int,      // 0..100
    val isCharging: Boolean
)

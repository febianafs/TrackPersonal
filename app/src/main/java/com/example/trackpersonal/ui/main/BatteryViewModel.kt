package com.example.trackpersonal.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.trackpersonal.battery.BatteryMonitor
import com.example.trackpersonal.battery.BatteryState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class BatteryViewModel(app: Application) : AndroidViewModel(app) {
    private val monitor = BatteryMonitor(app.applicationContext)

    private val _state = MutableStateFlow(BatteryState(levelPercent = 0, isCharging = false))
    val state: StateFlow<BatteryState> = _state

    fun start() {
        viewModelScope.launch {
            monitor.observe()
                .catch { /* ignore */ }
                .collect { _state.value = it }
        }
    }
}
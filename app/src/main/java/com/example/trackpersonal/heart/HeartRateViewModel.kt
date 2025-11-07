package com.example.trackpersonal.heart

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HeartRateViewModel(app: Application) : AndroidViewModel(app) {

    // 👇 pakai singleton yang sama dengan Service
    // Yang penting: sekarang UI dan service pegang instance repo yang sama
    private val repo = HeartRateRepoProvider.get(app)

    val state: StateFlow<HeartRateState> = repo.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, HeartRateState())

    fun start() = repo.start()

    /**
     * HATI-HATI:
     * Jangan dipanggil di onStop/onDestroy Activity,
     * biarkan Service yang pegang lifecycle BLE.
     * Stop ini cuma dipakai kalau izin BLE ditolak.
     */
    fun stop() = repo.stop()
}

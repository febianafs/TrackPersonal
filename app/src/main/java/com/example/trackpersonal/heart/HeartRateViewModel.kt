package com.example.trackpersonal.heart

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HeartRateViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = HeartRateRepository(app)

    val state: StateFlow<HeartRateState> = repo.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, HeartRateState())

    fun start() = repo.start()
    fun stop() = repo.stop()
}
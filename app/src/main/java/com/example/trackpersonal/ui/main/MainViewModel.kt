package com.example.trackpersonal.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.trackpersonal.data.model.LoginResponse
import com.example.trackpersonal.utils.SecurePref
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(
    private val securePref: SecurePref
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState

    fun loadCachedLogin() {
        viewModelScope.launch {
            val loginJson = securePref.getLoginResponse()
            if (loginJson.isNullOrBlank()) return@launch

            runCatching {
                Gson().fromJson(loginJson, LoginResponse::class.java)
            }.onSuccess { resp ->
                val data = resp.data
                val setting = data?.setting
                val profile = data?.user?.profile?.profile

                _uiState.value = MainUiState(
                    title = setting?.title ?: "--",
                    logoUrl = setting?.logo,
                    fullName = profile?.fullName ?: "--",
                    nrp = profile?.nrp ?: "--",
                    rank = profile?.rank?.name ?: "--",
                    unit = profile?.satuan?.name ?: "--",
                    battalion = profile?.batalyon?.name ?: "--",
                    squad = profile?.regu?.name ?: "--",
                    avatarUrl = profile?.avatarUrl
                )
            }
        }
    }
}

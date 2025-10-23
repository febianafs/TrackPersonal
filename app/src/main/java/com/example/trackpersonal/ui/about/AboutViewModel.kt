package com.example.trackpersonal.ui.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.trackpersonal.data.model.AboutResponse
import com.example.trackpersonal.data.repository.AboutRepository
import com.example.trackpersonal.utils.Resource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AboutViewModel(private val repo: AboutRepository) : ViewModel() {

    private val _aboutState = MutableStateFlow<Resource<AboutResponse>>(Resource.Idle())
    val aboutState: StateFlow<Resource<AboutResponse>> = _aboutState

    fun fetchAboutUs() {
        viewModelScope.launch {
            _aboutState.value = Resource.Loading()
            try {
                val response = repo.fetchAboutUs()
                if (response.isSuccessful) {
                    _aboutState.value = Resource.Success(response.body())
                } else {
                    _aboutState.value = Resource.Error("Gagal memuat data (${response.code()})")
                }
            } catch (e: Exception) {
                _aboutState.value = Resource.Error(e.message ?: "Unknown error")
            }
        }
    }
}
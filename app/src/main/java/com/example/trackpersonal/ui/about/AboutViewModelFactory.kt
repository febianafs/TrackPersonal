package com.example.trackpersonal.ui.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.trackpersonal.data.repository.AboutRepository

@Suppress("UNCHECKED_CAST")
class AboutViewModelFactory(
    private val repo: AboutRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AboutViewModel::class.java)) {
            return AboutViewModel(repo) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
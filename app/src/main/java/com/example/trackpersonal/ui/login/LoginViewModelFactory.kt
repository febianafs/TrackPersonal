package com.example.trackpersonal.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.trackpersonal.data.repository.AuthRepository
import com.example.trackpersonal.utils.SecurePref

class LoginViewModelFactory(private val securePref: SecurePref) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LoginViewModel::class.java)) {
            val repository = AuthRepository(securePref)
            @Suppress("UNCHECKED_CAST")
            return LoginViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
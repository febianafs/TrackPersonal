package com.example.trackpersonal.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.trackpersonal.data.model.LoginResponse
import com.example.trackpersonal.data.repository.AuthRepository
import com.example.trackpersonal.utils.Resource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LoginViewModel(private val repository: AuthRepository) : ViewModel() {

    private val _loginResult =
        MutableStateFlow<Resource<LoginResponse>>(Resource.Idle()) // default Idle
    val loginResult = _loginResult.asStateFlow()

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _loginResult.value = Resource.Loading()
            _loginResult.value = repository.login(email, password)
        }
    }
}

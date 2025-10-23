package com.example.trackpersonal.data.repository

import com.example.trackpersonal.data.model.LoginResponse
import com.example.trackpersonal.data.model.LogoutResponse
import com.example.trackpersonal.data.network.NetworkProvider
import com.example.trackpersonal.utils.Resource
import com.example.trackpersonal.utils.SecurePref
import retrofit2.Response

class AuthRepository(private val securePref: SecurePref) {

    private val api = NetworkProvider.provideApiService(securePref)

    suspend fun login(email: String, password: String): Resource<LoginResponse> {
        return try {
            val response: Response<LoginResponse> = api.login(email, password)
            if (response.isSuccessful && response.body() != null) {
                Resource.Success(response.body()!!)
            } else {
                val msg = response.errorBody()?.string().orEmpty().ifBlank { response.message() }
                Resource.Error("Login gagal: ${response.code()} - $msg")
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Terjadi kesalahan saat login")
        }
    }

    suspend fun logout(): Resource<LogoutResponse> {
        return try {
            val response: Response<LogoutResponse> = api.logout()
            if (response.isSuccessful && response.body() != null) {
                Resource.Success(response.body()!!)
            } else {
                val msg = response.errorBody()?.string().orEmpty().ifBlank { response.message() }
                Resource.Error("Logout gagal: ${response.code()} - $msg")
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Terjadi kesalahan saat logout")
        }
    }
}

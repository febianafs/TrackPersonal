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

    // AuthRepository.kt
    suspend fun logout(): Resource<LogoutResponse> {
        return try {
            val response = api.logout()
            if (response.isSuccessful && response.body() != null) {
                Resource.Success(response.body()!!)
            } else if (response.code() == 401) {
                // token expired → treat as logged out
                Resource.Success(
                    LogoutResponse(
                        success = true,
                        message = "Token expired; treated as logged out",
                        status_code = 200,
                        status_message = "OK"
                    )
                )
            } else {
                val msg = response.errorBody()?.string().orEmpty().ifBlank { response.message() }
                Resource.Error("Logout gagal: ${response.code()} - $msg")
            }
        } catch (e: Exception) {
            val t = e.message?.lowercase().orEmpty()
            if (t.contains("401") || t.contains("unauthorized") || t.contains("expired")) {
                Resource.Success(
                    LogoutResponse(
                        success = true,
                        message = "Logout Berhasil", //awalnya Token expired; treated as logged out
                        status_code = 200,
                        status_message = "OK"
                    )
                )
            } else {
                Resource.Error(e.message ?: "Terjadi kesalahan saat logout")
            }
        }
    }
}

package com.example.trackpersonal.data.repository

import com.example.trackpersonal.data.model.AboutResponse
import com.example.trackpersonal.data.network.NetworkProvider
import com.example.trackpersonal.utils.SecurePref
import retrofit2.Response

class AboutRepository(private val securePref: SecurePref) {

    private val api = NetworkProvider.provideApiService(securePref)

    suspend fun fetchAboutUs(): Response<AboutResponse> {
        return api.fetchAboutUs() // cukup ini, header akan ditambah oleh Interceptor
    }
}
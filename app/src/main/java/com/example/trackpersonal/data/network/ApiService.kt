package com.example.trackpersonal.data.network

import com.example.trackpersonal.data.model.AboutResponse
import com.example.trackpersonal.data.model.LoginResponse
import com.example.trackpersonal.data.model.LogoutResponse
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST

interface ApiService {
    @FormUrlEncoded
    @POST("api/mobile/auth/login")
    suspend fun login(
        @Field("email") email: String,
        @Field("password") password: String
    ): Response<LoginResponse>

    @POST("api/auth/logout")
    suspend fun logout()
            : Response<LogoutResponse>

    @GET("api/about-us")
    suspend fun fetchAboutUs(): Response<AboutResponse>

}
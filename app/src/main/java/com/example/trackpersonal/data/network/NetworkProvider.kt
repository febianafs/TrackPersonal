package com.example.trackpersonal.data.network

import com.example.trackpersonal.utils.SecurePref
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkProvider {

    private const val BASE_URL = "http://147.139.161.159:8001/"

    // Interceptor gabungan: Authorization + X-Android-Id
    private class AuthAndDeviceInterceptor(private val securePref: SecurePref) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val token = securePref.getToken()
            val androidId = securePref.getAndroidId().orEmpty()

            val builder = chain.request().newBuilder()

            if (!token.isNullOrEmpty()) {
                builder.addHeader("Authorization", "Bearer $token")
            }
            if (androidId.isNotEmpty()) {
                builder.addHeader("X-Android-Id", androidId)
            }

            return chain.proceed(builder.build())
        }
    }

    // Singleton Retrofit
    fun provideApiService(securePref: SecurePref): ApiService {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .addInterceptor(AuthAndDeviceInterceptor(securePref))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}

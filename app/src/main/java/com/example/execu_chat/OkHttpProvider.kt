package com.example.execu_chat

import android.content.Context
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object OkHttpProvider {
    @Volatile private var refreshClient: OkHttpClient? = null
    @Volatile private var authedClient: OkHttpClient? = null
    @Volatile private var authedSseClient: OkHttpClient? = null

    fun refreshClient(): OkHttpClient {
        return refreshClient ?: synchronized(this) {
            refreshClient ?: OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
                .also { refreshClient = it }
        }
    }
    fun authedClient(context: Context, baseUrl: String): OkHttpClient {
        return authedClient ?: synchronized(this) {
            authedClient ?: OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .addInterceptor(
                    JwtAuthInterceptor(
                        context = context.applicationContext,
                        gatewayUrl = baseUrl.trimEnd('/'),
                        refreshClient = refreshClient()
                    )
                )
                .build()
                .also { authedClient = it }
        }
    }
    fun authedSseClient(context: Context, baseUrl: String): OkHttpClient {
        return authedSseClient ?: synchronized(this) {
            authedSseClient ?: authedClient(context, baseUrl).newBuilder()
                .readTimeout(0, TimeUnit.SECONDS) // SSE
                .build()
                .also { authedSseClient = it }
        }
    }
}
package com.example.execu_chat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 *  Single client for all endpoints (chat, search, research)
 *  Authentication has been added why a gateway is useful aswell
 *  attach jwt bearer token to every request
 */

class GatewayClient(
    private val context: Context,
    private val gatewayUrl: String
    ) {
    private val JSON_TYPE = "application/json".toMediaType() // converts string to mediatype obj for request

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    data class AuthTokens(val accessToken: String, val refreshToken: String)

    suspend fun register(username: String, password: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("username", username)
                    put("password", password)
                }.toString().toRequestBody(JSON_TYPE)

                val request = Request.Builder()
                    .url("$gatewayUrl/register")
                    .post(body)
                    .build()

                httpClient.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        Result.success("Registered successfully")
                    } else {
                        val detail = runCatching { JSONObject(text).getString("detail") }
                            .getOrDefault("Registration failed (${resp.code})")
                        Result.failure(Exception(detail))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }


    suspend fun login(username: String, password: String): Result<AuthTokens> =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("username", username)
                    put("password", password)
                }.toString().toRequestBody(JSON_TYPE)

                val request = Request.Builder()
                    .url("$gatewayUrl/login")
                    .post(body)
                    .build()

                httpClient.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val j = JSONObject(text)
                        Result.success(
                            AuthTokens(
                                accessToken  = j.getString("access_token"),
                                refreshToken = j.getString("refresh_token")
                            )
                        )
                    } else {
                        val detail = runCatching { JSONObject(text).getString("detail") }
                            .getOrDefault("Login failed (${resp.code})")
                        Result.failure(Exception(detail))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun refreshTokens(): Result<AuthTokens> =
        withContext(Dispatchers.IO) {
            try {
                val refresh = TokenManager.refreshToken(context)
                    ?: return@withContext Result.failure(Exception("No refresh token"))

                val body = JSONObject().apply {
                    put("refresh_token", refresh)
                }.toString().toRequestBody(JSON_TYPE)

                val request = Request.Builder()
                    .url("$gatewayUrl/refresh")
                    .post(body)
                    .build()

                httpClient.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val j = JSONObject(text)
                        val tokens = AuthTokens(
                            accessToken  = j.getString("access_token"),
                            refreshToken = j.getString("refresh_token")
                        )
                        TokenManager.save(context, tokens.accessToken, tokens.refreshToken)
                        Result.success(tokens)
                    } else {
                        Result.failure(Exception("Token refresh failed"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun currentUserId(): String? = TokenManager.username(context)

    suspend fun isHealthy(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$gatewayUrl/health")
                .build()
            httpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }
    private fun requireToken(): String =
        TokenManager.accessToken(context)
            ?: throw RuntimeException("Not authenticated — login required")

    private fun handleResponse(response: Response) {
        if (response.code == 401) {
            throw UnauthorizedException("Token expired or invalid")
        }
        if (!response.isSuccessful) {
            throw RuntimeException("Request failed: ${response.code} ${response.message}")
        }
    }

    private fun String.urlEncode(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
/** Thrown on 401 — ViewModel can catch this to trigger refresh or logout. */
class UnauthorizedException(message: String) : RuntimeException(message)



package com.example.execu_chat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
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

    private val httpClient = OkHttpProvider.authedClient(context, gatewayUrl)

    private val sseClient = OkHttpProvider.authedSseClient(context, gatewayUrl)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    data class AuthTokens(val accessToken: String, val refreshToken: String)
    data class SaveChatResponse(val convoId: String, val savedPairs: Int)

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

    private fun assertSuccessful(response: Response) {
        if (response.code == 401) {
            throw UnauthorizedException("Token expired / invalid")
        }
        if (!response.isSuccessful) {
            throw RuntimeException("Request failed: ${response.code} ${response.message}")
        }
    }

    suspend fun createSession(): String = withContext(Dispatchers.IO) {
        val token = requireToken()

        val req = Request.Builder()
            .url("$gatewayUrl/session/create")
            .post("{}".toRequestBody(JSON_TYPE))
            .header("Authorization", "Bearer $token")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            assertSuccessful(resp)
            val text = resp.body?.string().orEmpty()
            val sessionId = JSONObject(text).getString("session_id")
            sessionId
        }
    }
    fun streamChat(
        sessionId: String,
        message: String,
        enableSearch: Boolean,
        enableRag: Boolean,
        model: String,
        temperature: Double,
        maxTokens: Int,
        onDelta: (String) -> Unit,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit
    ): EventSource {
        val token = requireToken()

        val payload = JSONObject().apply {
            put("session_id", sessionId)
            put("message", message)
            put("enable_search", enableSearch)
            put("enable_rag", enableRag)
            put("model", model)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
        }.toString()

        val req = Request.Builder()
            .url("$gatewayUrl/chat")
            .post(payload.toRequestBody(JSON_TYPE))
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                // gateway forwards OpenAI style: data: {...} and data: [DONE]
                if (data == "[DONE]") {
                    onDone()
                    eventSource.cancel()
                    return
                }

                try {
                    val obj = JSONObject(data)
                    val choices = obj.optJSONArray("choices") ?: return
                    if (choices.length() == 0) return
                    val c0 = choices.optJSONObject(0) ?: return
                    val delta = c0.optJSONObject("delta")
                    val content = delta?.optString("content", "") ?: ""
                    if (content.isNotEmpty()) onDelta(content)

                    val finish = c0.optString("finish_reason", null)
                    if (finish == "stop" || finish == "length") {
                        onDone()
                        eventSource.cancel()
                    }
                } catch (_: Exception) {
                    // ignore malformed SSE chunks
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                onError(t ?: RuntimeException("SSE failure"))
            }

            override fun onClosed(eventSource: EventSource) {
                // If server closes without [DONE], treat as done
                onDone()
            }
        }
        return EventSources.createFactory(sseClient).newEventSource(req, listener)
    }
    suspend fun saveChat(
        sessionId: String,
        title: String,
        convoId: String? = null
    ): SaveChatResponse = withContext(Dispatchers.IO) {
        val token = requireToken()

        val payload = JSONObject().apply {
            put("session_id", sessionId)
            put("title", title)
            if (convoId != null ) put("conv_id", convoId)
        }.toString()

        val req = Request.Builder()
            .url("$gatewayUrl/save/save_chat")
            .post(payload.toRequestBody(JSON_TYPE))
            .header("Authorization", "Bearer $token")
            .build()

        val response = httpClient.newCall(req).execute()
        response.use { resp ->
            assertSuccessful(resp)
            val text = resp.body?.string().orEmpty()
            val j = JSONObject(text)
            val result = SaveChatResponse(
                convoId = j.getString("convo_id"),
                savedPairs = j.optInt("saved_pairs", 0)
            )
            result
        }
    }

    suspend fun listChats(
        limit: Int = 50,
        offset: Int = 0,
    ): List<JSONObject> = withContext(Dispatchers.IO) {
        val token = requireToken()

        
        val req = Request.Builder()
            .url("$gatewayUrl/save/chat_list?limit=$limit&offset=$offset")
            .get()
            .header("Authorization", "Bearer $token")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            assertSuccessful(resp)
            val text = resp.body?.string().orEmpty()
            val arr = JSONArray(text)
            List(arr.length()) {i -> arr.getJSONObject(i)}
        }
    }

    suspend fun loadChat(convoId: String): JSONObject =
        withContext(Dispatchers.IO) {
            val token = requireToken()

            val req = Request.Builder()
                .url("$gatewayUrl/save/load_chat/$convoId")
                .get()
                .header("Authorization", "Bearer $token")
                .build()

            httpClient.newCall(req).execute().use {resp ->
                assertSuccessful(resp)
                val text = resp.body?.string().orEmpty()
                JSONObject(text)
            }
        }
    suspend fun deleteChat(convoId: String): Unit = withContext(Dispatchers.IO) {
        val token = requireToken()

        val req = Request.Builder()
            .url("$gatewayUrl/save/delete_chat/$convoId")
            .delete()
            .header("Authorization", "Bearer $token")
            .build()

        val response = httpClient.newCall(req).execute()
        response.use { resp ->
            assertSuccessful(resp)
        }
    }

    private fun String.urlEncode(): String =
        java.net.URLEncoder.encode(this, "UTF-8")


}
/** Thrown on 401 — ViewModel can catch this to trigger refresh or logout. */
class UnauthorizedException(message: String) : RuntimeException(message)



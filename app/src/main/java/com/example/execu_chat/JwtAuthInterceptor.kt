package com.example.execu_chat

import android.content.Context
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

class JwtAuthInterceptor(
    private val context: Context,
    private val gatewayUrl: String,
    private val refreshClient: okhttp3.OkHttpClient
) : Interceptor {

    private val JSON_TYPE = "application/json".toMediaType()

    private fun isPublicPath(path: String): Boolean {
        return path == "/login" ||
                path == "/register" ||
                path == "/refresh" ||
                path == "/health"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val path = original.url.encodedPath

        // no auth on public endpoints
        if (isPublicPath(path) || original.header("No-Auth") == "true") {
            return chain.proceed(original)
        }

        val access = TokenManager.accessToken(context)
        val authed = if (!access.isNullOrBlank()) {
            original.newBuilder()
                .header("Authorization", "Bearer $access")
                .build()
        } else original

        val response = chain.proceed(authed)
        if (response.code != 401) return response

        // no loops, no double retry
        if (path == "/refresh" || authed.header("X-Retry") == "1") return response

        response.close()
        // Retry bit
        val refreshed = synchronized(this) {
            val currentAccess = TokenManager.accessToken(context)
            if (currentAccess != null && currentAccess != access) {
                true
            } else {
                refreshTokensBlocking()
            }
        }
        if (!refreshed) {
            TokenManager.clear(context)
            // give back a 401-ish outcome by retrying once without looping
            return chain.proceed(original.newBuilder().header("X-Retry", "1").build())
        }

        val newAccess = TokenManager.accessToken(context)
        if (newAccess.isNullOrBlank()) {
            TokenManager.clear(context)
            return chain.proceed(original.newBuilder().header("X-Retry", "1").build())
        }

        val retry = original.newBuilder()
            .header("Authorization", "Bearer $newAccess")
            .header("X-Retry", "1")
            .build()

        return chain.proceed(retry)
    }

    private fun refreshTokensBlocking(): Boolean {
        val refresh = TokenManager.refreshToken(context) ?: return false

        val body = JSONObject()
            .put("refresh_token", refresh)
            .toString()
            .toRequestBody(JSON_TYPE)

        val req = Request.Builder()
            .url("${gatewayUrl.trimEnd('/')}/refresh")
            .post(body)
            .build()

        return try {
            refreshClient.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return false
                val j = JSONObject(text)
                TokenManager.save(
                    context,
                    j.getString("access_token"),
                    j.getString("refresh_token")
                )
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
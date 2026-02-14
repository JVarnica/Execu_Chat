package com.example.execu_chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class SearchResult(
    val title: String = "",
    val url: String = "",
    val content: String = "",
    val engine: String = ""
)

@Serializable
data class SearchResponse(
    val results: List<SearchResult> = emptyList(),
    val query: String = ""
)

class SearchClient(
    private val baseUrl: String = "http://192.168.1.14:8080"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun search(
        query: String,
        maxResults: Int = 5
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/search?q=${query.urlEncode()}&format=json"

            val request = Request.Builder()
                .url(url)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext emptyList()
                }

                val body = response.body ?: return@withContext emptyList()
                val bodyString = body.string()
                val searchResponse = json.decodeFromString<SearchResponse>(bodyString)

                searchResponse.results.take(maxResults)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun String.urlEncode(): String {
        return java.net.URLEncoder.encode(this, "UTF-8")
    }
}
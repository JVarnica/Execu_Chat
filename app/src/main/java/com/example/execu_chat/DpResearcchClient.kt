package com.example.execu_chat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

@Serializable
data class ResearchTask(
    val task_id: String = "",
    val status: String = "",
    val query: String = "",
    val created_at: String = "",
    val report: String? = null,
    val error: String? = null
)
//represents event. type is status,search,summary etc. need to know what it is doing
data class ResearchEvent(
    val type: String,
    val data: JsonObject
)


class DeepResearchClient(
    private val context: Context,
    private val baseUrl: String
    ) {
        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)   // SSE streams indefinitely
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        // ── Submit a research task ────────────────────────────────────────

        /**
         * POST /research – returns the created task (with task_id).
         */
        suspend fun submitResearch(
            query: String,
            maxSearches: Int = 8,
            maxResultsPerSearch: Int = 5
        ): ResearchTask = withContext(Dispatchers.IO) {
            val body = """
            {
                "query": ${json.encodeToString(kotlinx.serialization.serializer(), query)},
                "max_searches": $maxSearches,
                "max_results_per_search": $maxResultsPerSearch
            }
        """.trimIndent()

            val request = Request.Builder()
                .url("$baseUrl/research")
                .addHeader("Authorization", "Bearer ${TokenManager.accessToken(context)}")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw RuntimeException("Submit failed: ${response.code} ${response.message}")
                }
                val responseBody = response.body?.string()
                    ?: throw RuntimeException("Empty response")
                json.decodeFromString<ResearchTask>(responseBody)
            }
        }

        // ── Poll for result ───────────────────────────────────────────────

        /**
         * GET /research/{id} – get task status and final report.
         */
        suspend fun getResearch(taskId: String): ResearchTask = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/research/$taskId")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw RuntimeException("Fetch failed: ${response.code}")
                }
                val responseBody = response.body?.string()
                    ?: throw RuntimeException("Empty response")
                json.decodeFromString<ResearchTask>(responseBody)
            }
        }

        // ── Cancel task ───────────────────────────────────────────────────

        suspend fun cancelResearch(taskId: String) = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/research/$taskId")
                .delete()
                .build()
            client.newCall(request).execute().close()
        }

        // ── SSE stream of progress events ─────────────────────────────────

        /**
         * Connects to GET /research/{id}/stream and emits [ResearchEvent]s
         * as a Kotlin Flow. The flow completes when the task finishes.
         *
         * Usage in ViewModel:
         * ```
         * val task = client.submitResearch("How does quantum computing work?")
         * client.streamEvents(task.task_id).collect { event ->
         *     when (event.type) {
         *         "status"  -> updatePhaseUI(event.data)
         *         "search"  -> showSearchProgress(event.data)
         *         "source"  -> addSourceCard(event.data)
         *         "summary" -> showSummary(event.data)
         *         "report"  -> showFinalReport(event.data)
         *         "error"   -> showError(event.data)
         *     }
         * }
         * ```
         */
        fun streamEvents(taskId: String): Flow<ResearchEvent> = callbackFlow {
            val request = Request.Builder()
                .url("$baseUrl/research/$taskId/stream")
                .header("Accept", "text/event-stream")
                .build()

            val listener = object : EventSourceListener() {

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    try {
                        val parsed = json.parseToJsonElement(data) as? JsonObject ?: return
                        val eventType = type
                            ?: parsed["type"]?.jsonPrimitive?.content
                            ?: "status"

                        trySend(ResearchEvent(type = eventType, data = parsed))

                        // Auto-close on terminal events
                        if (eventType == "report" || eventType == "error") {
                            channel.close()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    channel.close()
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?
                ) {
                    channel.close(t ?: RuntimeException("SSE connection failed"))
                }
            }

            val factory = EventSources.createFactory(client)
            val eventSource = factory.newEventSource(request, listener)

            awaitClose {
                eventSource.cancel()
            }
        }
    }
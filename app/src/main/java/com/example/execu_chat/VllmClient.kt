package com.example.execu_chat

import android.content.Context
import android.util.Log
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.chat.completions.ChatCompletionCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class VllmClient(
    private val context: Context,
    private val defaultModel: String,
    private val gatewayUrl: String
) {
    private val JSON_TYPE = "application/json".toMediaType()
    private val client = OkHttpProvider.authedSseClient(context, gatewayUrl)

    suspend fun streamChatCompletion(
        messages: List<ChatMessage>,
        model: String = defaultModel,
        temperature: Double = 0.7,
        maxTokens: Int = 2048,
        onDelta: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {

        val payload = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("temperature", temperature)
            // OpenAI uses max_tokens; newer has max_completion_tokens
            // Your gateway/vLLM may accept either. Keep max_tokens for compatibility.
            put("max_tokens", maxTokens)

            val arr = JSONArray()
            messages.forEach { msg ->
                val role = when (msg.role) {
                    ChatMessage.Role.System -> "system"
                    ChatMessage.Role.User -> "user"
                    ChatMessage.Role.Assistant -> "assistant"
                }
                arr.put(JSONObject().apply {
                    put("role", role)
                    put("content", msg.text)
                })
            }
            put("messages", arr)
        }.toString()

        val url = gatewayUrl.trimEnd('/') + "/v1/chat/completions"

        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_TYPE))
            .build()

        val full = StringBuilder()
        val latch = CountDownLatch(1)
        val errorRef = AtomicReference<Throwable?>(null)

        val listener = object : EventSourceListener() {

            override fun onOpen(eventSource: EventSource, response: Response) {
                // no-op
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                // OpenAI stream ends with [DONE]
                if (data.trim() == "[DONE]") {
                    eventSource.cancel()
                    latch.countDown()
                    return
                }

                try {
                    val json = JSONObject(data)
                    val choices = json.getJSONArray("choices")
                    if (choices.length() > 0) {
                        val choice = choices.getJSONObject(0)
                        val delta = choice.getJSONObject("delta")
                        val content = delta.optString("content", "")

                        if (content.isNotEmpty()) {
                            full.append(content)
                            onDelta(content)
                        }

                        // Stream is done when finish_reason is non-null
                        val finishReason = choice.optString("finish_reason", "")
                        if (finishReason.isNotEmpty() && finishReason != "null") {
                            eventSource.cancel()
                            latch.countDown()
                            return
                        }
                    }
                } catch (e: Exception) {
                    errorRef.set(e)
                    eventSource.cancel()
                    latch.countDown()
                }
            }

            override fun onClosed(eventSource: EventSource) {
                latch.countDown()
                Log.d("SSE", "onClosed called")
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                if (t is IOException && t.message == "canceled") {
                    Log.d("SSE", "Stream closed cleanly")
                    return
                }

                Log.e("SSE", "onFailure: ${t?.message} code=${response?.code}")
                latch.countDown()
            }
        }

        val factory = EventSources.createFactory(client)
        val es = factory.newEventSource(request, listener)

        // Wait until stream completes (or fails)
        val ok = latch.await(10, TimeUnit.MINUTES)
        if (!ok) {
            es.cancel()
            throw RuntimeException("SSE timed out")
        }

        errorRef.get()?.let { throw it }

        full.toString()
    }
}



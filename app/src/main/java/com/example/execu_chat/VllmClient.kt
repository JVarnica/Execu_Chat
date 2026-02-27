package com.example.execu_chat

import android.content.Context
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.chat.completions.ChatCompletionCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VllmClient(
    private val context: Context,
    private val defaultModel: String,
    private val gatewayUrl: String
) {
    // Rebuild when token refreshes
    private var cachedToken: String? = null
    private var client: OpenAIClient? = null

    private fun getClient(): OpenAIClient {
        val token = TokenManager.accessToken(context) ?: "none"
        if (token != cachedToken || client == null) {
            cachedToken = token
            client = OpenAIOkHttpClient.builder()
                .baseUrl(gatewayUrl.trimEnd('/'))
                .apiKey(token)   // JWT goes as Bearer — gateway accepts it
                .build()
        }
        return client!!
    }

    /**
     * Stream chat completion with delta callbacks
     * @param messages List of ChatMessage
     * @param model Model name (defaults to defaultModel)
     * @param temperature Sampling temperature (0.0 - 2.0)
     * @param maxTokens Maximum tokens to generate
     * @param onDelta Callback for each text chunk as it arrives
     * @return Complete response text
     */
    suspend fun streamChatCompletion(
        messages: List<ChatMessage>,
        model: String = defaultModel,
        temperature: Double = 0.7,
        maxTokens: Int = 2048,
        onDelta: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val params = ChatCompletionCreateParams.builder()
            .model(model)
            .temperature(temperature)
            .maxCompletionTokens(maxTokens.toLong())
            .apply {
                messages.forEach { msg ->
                    when (msg.role) {
                        ChatMessage.Role.System -> addSystemMessage(msg.text)
                        ChatMessage.Role.User -> addUserMessage(msg.text)
                        ChatMessage.Role.Assistant -> addAssistantMessage(msg.text)
                    }
                }
            }
            .build()

        val fullResponse = StringBuilder()

        getClient().chat().completions().createStreaming(params).use { stream ->
            stream.stream().forEach { chunk ->
                chunk.choices().firstOrNull()?.delta()?.content()?.orElse(null)?.let { delta ->
                    fullResponse.append(delta)
                    onDelta(delta)
                }
            }
        }

        fullResponse.toString()
    }
}

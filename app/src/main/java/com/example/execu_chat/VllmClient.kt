package com.example.execu_chat

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.chat.completions.ChatCompletionCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VllmClient(
    private val baseUrl: String, // e.g. "http://192.168.1.50:8000/v1"
    private val defaultModel: String,
    apiKey: String? = null
) {
    private val client: OpenAIClient = OpenAIOkHttpClient.builder()
        .baseUrl(baseUrl.trimEnd('/'))
        .apply {
            if (!apiKey.isNullOrBlank()) {
                apiKey(apiKey)
            } else {
                // vLLM often doesn't require a real key, but library needs something
                apiKey("dummy-key")
            }
        }
        .build()

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

        client.chat().completions().createStreaming(params).use { stream ->
            stream.stream().forEach { chunk ->
                chunk.choices().firstOrNull()?.delta()?.content()?.orElse(null)?.let { delta ->
                    fullResponse.append(delta)
                    onDelta(delta)
                }
            }
        }

        fullResponse.toString()
    }

    /**
     * Non-streaming chat completion (waits for full response)
     */
    suspend fun chatOnce(
        messages: List<ChatMessage>,
        model: String = defaultModel,
        temperature: Double = 0.7,
        maxTokens: Int = 512
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

        val completion = client.chat().completions().create(params)
        completion.choices().firstOrNull()?.message()?.content()?.orElse("")
            ?: throw RuntimeException("No response from model")
    }
}
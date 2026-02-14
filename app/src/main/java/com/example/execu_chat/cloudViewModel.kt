package com.example.execu_chat

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ResearchProgress(
    val phase: String = "queued",          // planning, searching, reading, synthesising, done
    val message: String = "",
    val progress: Float = 0f,              // 0.0 → 1.0
    val currentQuestion: String = "",
    val sourcesFound: MutableList<SourceItem> = mutableListOf(),
    val summaries: MutableList<String> = mutableListOf(),
    val subQuestions: List<String> = emptyList(),
)
data class SourceItem(
    val title: String,
    val url: String,
    val snippet: String = ""
)

class CloudChatViewModel : ViewModel() {
    private val vllm = VllmClient(
        baseUrl = ServerConfig.VLLM_BASE_URL,
        defaultModel = ServerConfig.DEFAULT_MODEL,
        apiKey = ServerConfig.VLLM_API_KEY
    )
    private val searchClient = SearchClient(
        baseUrl = ServerConfig.SEARCH_BASE_URL
    )
    private val researchClient = DeepResearchClient(baseUrl = ServerConfig.RESEARCH_BASE_URL)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // ── Research state (new) ─────────────────────────────────────────
    private val _isResearching = MutableStateFlow(false)
    val isResearching: StateFlow<Boolean> = _isResearching.asStateFlow()

    private val _researchProgress = MutableStateFlow<ResearchProgress?>(null)
    val researchProgress: StateFlow<ResearchProgress?> = _researchProgress.asStateFlow()

    private var researchJob: Job? = null
    private var currentTaskId: String? = null
    private var currentChatId: String? = null

    fun sendMessage(text: String, enableSearch: Boolean = false) {
        if (text.isBlank() || _isLoading.value) return

        // Add user message
        val userMsg = ChatMessage(ChatMessage.Role.User, text)
        val currentMessages = _messages.value.toMutableList()
        currentMessages.add(userMsg)
        _messages.value = currentMessages

        // Add empty assistant placeholder
        val emptyAssistant = ChatMessage(ChatMessage.Role.Assistant, "")
        currentMessages.add(emptyAssistant)
        val assistantIndex = currentMessages.lastIndex
        _messages.value = currentMessages

        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            val responseText = StringBuilder()
            try {
                // Messages for API (without the empty placeholder)
                var messagesForApi = currentMessages.dropLast(1)

                if (enableSearch) {
                    val searchResults = searchClient.search(text)
                    if (searchResults.isNotEmpty()) {
                        val searchContext = formatSearchResults(searchResults)
                        val searchMessage = ChatMessage(
                            ChatMessage.Role.System,
                            searchContext)
                        messagesForApi = listOf(searchMessage) + messagesForApi
                    }

                }
                vllm.streamChatCompletion(
                    messages = messagesForApi,
                    onDelta = { chunk ->
                        responseText.append(chunk)

                        // Update assistant message
                        val updatedMessages = _messages.value.toMutableList()
                        updatedMessages[assistantIndex] = ChatMessage(
                            ChatMessage.Role.Assistant,
                            responseText.toString()
                        )
                        _messages.value = updatedMessages
                    }
                )

            } catch (e: Exception) {
                e.printStackTrace()
                _error.value = e.message ?: "Unknown error"

                // Update with error message
                val updatedMessages = _messages.value.toMutableList()
                updatedMessages[assistantIndex] = ChatMessage(
                    ChatMessage.Role.Assistant,
                    "Error: ${e.message ?: e.javaClass.simpleName}"
                )
                _messages.value = updatedMessages

            } finally {
                _isLoading.value = false
            }
        }
    }
    /**
     * Launch a deep research task. The UI should observe [researchProgress]
     * to show a live progress panel (sources being found, summaries, etc).
     *
     * When complete, the report is appended as an assistant message.
     */
    fun startDeepResearch(query: String) {
        if (query.isBlank() || _isResearching.value) return

        // Add user message to chat
        val userMsg = ChatMessage(ChatMessage.Role.User, "🔬 Deep Research: $query")
        _messages.value = _messages.value + userMsg

        _isResearching.value = true
        _researchProgress.value = ResearchProgress(phase = "queued", message = "Submitting…")

        researchJob = viewModelScope.launch {
            try {
                // 1. Submit task
                Log.d("RESEARCH", "Submitting query: $query")
                val task = researchClient.submitResearch(query)
                currentTaskId = task.task_id

                _researchProgress.value = ResearchProgress(
                    phase = "planning",
                    message = "Research task submitted, planning…"
                )

                // 2. Stream events
                researchClient.streamEvents(task.task_id).collect { event ->
                    Log.d("RESEARCH", "SSE event: ${event.type}")
                    val data = event.data
                    val progress = _researchProgress.value ?: ResearchProgress()

                    when (event.type) {
                        "status" -> {
                            val phase = data["phase"]?.jsonPrimitive?.content ?: progress.phase
                            val message = data["message"]?.jsonPrimitive?.content ?: ""
                            val prog = data["progress"]?.jsonPrimitive?.content?.toFloatOrNull()
                                ?: progress.progress

                            _researchProgress.value = progress.copy(
                                phase = phase,
                                message = message,
                                progress = prog,
                            )
                        }

                        "source" -> {
                            val title = data["title"]?.jsonPrimitive?.content ?: ""
                            val url = data["url"]?.jsonPrimitive?.content ?: ""
                            val snippet = data["snippet"]?.jsonPrimitive?.content ?: ""
                            progress.sourcesFound.add(SourceItem(title, url, snippet))
                            _researchProgress.value = progress.copy() // trigger recompose
                        }

                        "summary" -> {
                            val summary = data["summary"]?.jsonPrimitive?.content ?: ""
                            progress.summaries.add(summary)
                            _researchProgress.value = progress.copy()
                        }

                        "report" -> {
                            val markdown = data["markdown"]?.jsonPrimitive?.content ?: ""
                            // Inject the report as an assistant message
                            val reportMsg = ChatMessage(ChatMessage.Role.Assistant, markdown)
                            _messages.value = _messages.value + reportMsg

                            _researchProgress.value = progress.copy(
                                phase = "done",
                                message = "Research complete",
                                progress = 1f,
                            )
                        }

                        "error" -> {
                            val errorMsg = data["message"]?.jsonPrimitive?.content
                                ?: "Research failed"
                            _error.value = errorMsg
                            _researchProgress.value = progress.copy(
                                phase = "error",
                                message = errorMsg,
                            )
                        }
                    }
                }
                Log.d("RESEARCH", "Stream completed")
            } catch (e: Exception) {
                Log.e("RESEARCH", "Failed: ${e.message}", e)
                e.printStackTrace()
                _error.value = "Research failed: ${e.message}"

                // Fallback: poll for the result in case SSE broke
                currentTaskId?.let { id ->
                    try {
                        val result = researchClient.getResearch(id)
                        if (result.report != null) {
                            val reportMsg = ChatMessage(ChatMessage.Role.Assistant, result.report)
                            _messages.value = _messages.value + reportMsg
                        }
                    } catch (_: Exception) { }
                }
            } finally {
                _isResearching.value = false
                Log.d("RESEARCH", "Done, isResearching=false")
            }
        }
    }
    fun cancelDeepResearch() {
        researchJob?.cancel()
        currentTaskId?.let { id ->
            viewModelScope.launch {
                try { researchClient.cancelResearch(id) } catch (_: Exception) { }
            }
        }
        _isResearching.value = false
        _researchProgress.value = null
    }

    fun clearMessages() {
        _messages.value = emptyList()
        currentChatId = null
    }

    fun saveCurrentChat(context: Context) {
        val messages = _messages.value
        if (messages.isEmpty() || messages.all { it.text.isBlank() }) {
            Toast.makeText(context, "Nothing to save", Toast.LENGTH_SHORT).show()
            return
        }
        val transcript = buildTranscript(messages)
        if (transcript.isBlank()) return
        if (currentChatId != null ) {
            ChatStore.update(context, currentChatId!!, transcript)
        } else {
            val newThread = ChatStore.save(context, transcript)
            currentChatId = newThread.id
        }
    }

    fun loadChat(context: Context, thread: ChatThread) {
        val transcript = ChatStore.load(thread)
        val messages = parseTranscript(transcript)
        _messages.value = messages
        currentChatId = thread.id
    }

    fun getSavedChats(context: Context): List<ChatThread> {
        return ChatStore.list(context)
    }

    fun deleteChat(context: Context, thread: ChatThread) {
        // You'll need to add this to ChatStore
        ChatStore.delete(context, thread.id)
         if (currentChatId == thread.id) {
             clearMessages()
         }
    }

    private fun buildTranscript(messages: List<ChatMessage>): String {
        return messages.joinToString("\n") { msg ->
            val (_, cleanContent) = ChatMessage.extractCleanContent(msg.text)
            val rolePrefix = when (msg.role) {
                ChatMessage.Role.User -> "User: ${cleanContent}"
                ChatMessage.Role.Assistant -> "Assistant: ${cleanContent}"
                ChatMessage.Role.System -> "System: ${cleanContent}"
            }
            "$rolePrefix: $cleanContent"
        }
    }
    private fun parseTranscript(transcript: String): List<ChatMessage> {
        return transcript.split("\n---MESSAGE---\n")
            .mapNotNull { block ->
                val trimmed = block.trim()
                when {
                    trimmed.startsWith("User: ") -> ChatMessage(
                        ChatMessage.Role.User,
                        trimmed.removePrefix("User: ")
                    )
                    trimmed.startsWith("Assistant: ") -> ChatMessage(
                        ChatMessage.Role.Assistant,
                        trimmed.removePrefix("Assistant: ")
                    )
                    trimmed.startsWith("System: ") -> ChatMessage(
                        ChatMessage.Role.System,
                        trimmed.removePrefix("System: ")
                    )
                    else -> null
                }
            }
    }
    private fun formatSearchResults(results: List<SearchResult>): String {
        return buildString {
            appendLine("Here are relevant search results to help answer the question:")
            appendLine()
            results.forEachIndexed { index, result ->
                appendLine("${index + 1}. ${result.title}")
                appendLine("   URL: ${result.url}")
                if (result.content.isNotBlank()) {
                    appendLine("   ${result.content.take(200)}...")
                }
                appendLine()
            }
            appendLine("Use this information to provide an accurate, up-to-date answer.")
        }
    }
}

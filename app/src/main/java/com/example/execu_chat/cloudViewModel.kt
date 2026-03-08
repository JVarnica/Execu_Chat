package com.example.execu_chat

import android.app.Application
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException
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
/** Represents a saved conversation from the server. */
data class ChatListItem(
    val convoId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

class CloudChatViewModel(application: Application) : AndroidViewModel(application) {
    private val gateway = GatewayClient(getApplication(), ServerConfig.GATEWAY_URL)
    private val researchClient = DeepResearchClient(getApplication(), baseUrl = ServerConfig.GATEWAY_URL)
    //Chat Messages
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    //loading/ error
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    //server health
    private val _serverHealthy = MutableStateFlow(false)
    val serverHealthy: StateFlow<Boolean> = _serverHealthy.asStateFlow()

    // ── Research state (new) ─────────────────────────────────────────
    private val _isResearching = MutableStateFlow(false)
    val isResearching: StateFlow<Boolean> = _isResearching.asStateFlow()
    private val _researchProgress = MutableStateFlow<ResearchProgress?>(null)
    val researchProgress: StateFlow<ResearchProgress?> = _researchProgress.asStateFlow()
    private var researchJob: Job? = null
    private var currentTaskId: String? = null
    //gateway session id
    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()
    //saved chats list
    private val _serverChats = MutableStateFlow<List<ChatListItem>>(emptyList())
    val serverChats: StateFlow<List<ChatListItem>> = _serverChats.asStateFlow()

    private var currentConvoId: String? = null
    fun currentUserId(): String? = gateway.currentUserId()

    init {
        viewModelScope.launch {
            while (true) {
                _serverHealthy.value = gateway.isHealthy()
                delay(30_000)
            }
        }
    }
    fun checkHealthNow() {
        viewModelScope.launch {
            _serverHealthy.value = gateway.isHealthy()
        }
    }
    private suspend fun ensureSession(): String {
        val existing = _sessionId.value
        if (!existing.isNullOrBlank()) return existing
        val sid = gateway.createSession()
        _sessionId.value = sid
        return sid
    }

    fun newServerSession() {
        viewModelScope.launch {
            try {
                val sid = gateway.createSession()
                _sessionId.value = sid
                _messages.value = emptyList()
                _error.value = null
                currentConvoId = null
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }
    fun sendMessage(text: String, enableSearch: Boolean = false, enableRag: Boolean = false) {
        if (text.isBlank() || _isLoading.value) return

        // Add user message
        val userMsg = ChatMessage(ChatMessage.Role.User, text)
        val currentMessages = _messages.value.toMutableList()
        currentMessages.add(userMsg)
        //_messages.value = currentMessages

        // Add empty assistant placeholder
        val emptyAssistant = ChatMessage(ChatMessage.Role.Assistant, "")
        currentMessages.add(emptyAssistant)
        val assistantIndex = currentMessages.lastIndex
        _messages.value = currentMessages

        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                val sid = ensureSession()

                suspendCancellableCoroutine { cont ->
                    val responseText = StringBuilder()
                    val eventSource = gateway.streamChat(
                        sessionId = sid,
                        message = text,
                        enableSearch = enableSearch,
                        enableRag = enableRag,
                        model = ServerConfig.DEFAULT_MODEL,
                        temperature = 0.7,
                        maxTokens = 4096,
                        onDelta = { chunk ->
                            responseText.append(chunk)
                            val updated = _messages.value.toMutableList()
                            updated[assistantIndex] = ChatMessage(ChatMessage.Role.Assistant, responseText.toString())
                            _messages.value = updated
                        },
                        onDone = {
                            val updated = _messages.value.toMutableList()
                            if (assistantIndex < updated.size) {
                                updated[assistantIndex] =
                                    ChatMessage(ChatMessage.Role.Assistant, responseText.toString())
                                _messages.value = updated
                            }
                            if (cont.isActive) cont.resume(Unit) {}
                        },
                        onError = { e ->
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                    )
                    cont.invokeOnCancellation { eventSource.cancel()}
                }
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
                            Log.e("RESEARCH", "Error from server: $errorMsg")
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
        currentConvoId = null
        _sessionId.value = null
    }

    fun saveCurrentSession(title: String) {
        val sid = sessionId.value ?: return
        viewModelScope.launch {
            try {
                val resp = gateway.saveChat(sessionId = sid, title = title, convoId = currentConvoId)
                Log.d("SAVE", "Saved convo=${resp.convoId}, pairs=${resp.savedPairs}")
                currentConvoId = resp.convoId

                // Server deleted the redis session keys, so get a fresh one
                _sessionId.value = gateway.createSession()

                // Refresh sidebar
                refreshChatList()
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }
    fun refreshChatList() {
        viewModelScope.launch {
            try {
                val arr = gateway.listChats(limit = 50, offset = 0)
                _serverChats.value = arr.map { obj ->
                    ChatListItem(
                        convoId = obj.optString("convo_id", ""),
                        title = obj.optString("title", "(untitled)"),
                        createdAt = obj.optLong("created_at", 0),
                        updatedAt = obj.optLong("updated_at", 0),
                    )
                }
            } catch (e: Exception) {
                Log.e("CHATS", "Failed to list chats: ${e.message}")
                _error.value = e.message
            }
        }
    }
    fun loadChat(convoId: String) {
        viewModelScope.launch {
            try {
                val convo = gateway.loadChat(convoId)
                currentConvoId = convoId

                val msgsArray = convo.optJSONArray("messages")
                if (msgsArray != null) {
                    val chatMessages = mutableListOf<ChatMessage>()
                    for (i in 0 until msgsArray.length()) {
                        val m = msgsArray.getJSONObject(i)
                        val role = when (m.optString("role")) {
                            "user" -> ChatMessage.Role.User
                            "assistant" -> ChatMessage.Role.Assistant
                            else -> continue
                        }
                        val content = m.optString("content", "")
                        chatMessages.add(ChatMessage(role, content))
                    }
                    _messages.value = chatMessages
                }

                // Fresh redis session with old convo/pairs replayed
                val sid = convo.optString("session_id", "")
                _sessionId.value = sid.ifBlank { null }
            } catch (e: Exception) {
                Log.e("CHATS", "Failed to load chat: ${e.message}")
                _error.value = e.message
            }
        }
    }


    fun deleteChat(convoId: String) {
        viewModelScope.launch {
            try {
                gateway.deleteChat(convoId)
                _serverChats.value = _serverChats.value.filter { it.convoId != convoId }

                // If we just deleted the currently loaded chat, clear the screen
                if (currentConvoId == convoId) {
                    _messages.value = emptyList()
                    _sessionId.value = null
                    currentConvoId = null
                }
            } catch (e: Exception) {
                Log.e("CHATS", "Failed to delete chat: ${e.message}")
                _error.value = e.message
            }
        }
    }
}

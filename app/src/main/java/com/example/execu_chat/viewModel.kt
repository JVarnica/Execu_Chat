package com.example.execu_chat

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.app.Application
import android.content.ContentResolver
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.pytorch.executorch.extension.asr.AsrModule
import org.pytorch.executorch.extension.asr.AsrCallback
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmModule
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isModelLoaded: Boolean = false,
    val isWhisperLoaded: Boolean = false,
    val isGenerating: Boolean = false,
    val isAsrModelLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val isTranscribing: Boolean = false,
    val currentChatId: String? = null,
    val selectedModelConfig: ModelConfig = ModelConfigs.ALL.first { it.modelType == ModelType.LLAMA_3 },
    val selectedBackend: BackendType = BackendType.XNNPACK,
    val userSystemPrompt: String = ChatFormatter.DEFAULT_SYS_PROMPT,
    val statsText: String = "",
    val showStats: Boolean = false,
    val toastMessage: String? = null,
    val transcribedText: String? = null  // Result from Whisper
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    //threadin
    private val executorchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // llm and asr modules
    @Volatile
    private var llmModule: LlmModule? = null

    @Volatile
    private var whisperAsr: AsrModule? = null

    private val _messages = mutableListOf<ChatMessage>()
    private var needsPrefill = true
    private var shouldAddSysPrompt = true

    @Volatile
    private var lastUiUpdateMs = 0L

    private val context get() = getApplication<Application>()

    private val WHISPER_PREPROC_ASSET = "whisper_preprocessor1.pte"
    private val WHISPER_MODEL_ASSET = "whisper-tiny1.pte"
    private val WHISPER_TOKENIZER_ASSET = "whisper-tokenizer.json"

    //------ session & messages methods

    private fun clearMessages() {
        _messages.clear()
        _uiState.value = _uiState.value.copy(messages = emptyList())
    }

    private fun appendUser(text: String) {
        _messages.add(ChatMessage(ChatMessage.Role.User, text))
        _uiState.value = _uiState.value.copy(messages = _messages.toList())
    }

    private fun buildFullPrompt(): String {
        val modelType = _uiState.value.selectedModelConfig.modelType
        return ChatFormatter.buildFullPrompt(modelType, _messages)
    }

    private fun fullTranscript(): String {
        return _messages.joinToString("\n") { "${it.role}: ${it.text}" }
    }
    fun resetFromTranscript(text: String) {
        _messages.clear()
        var currentRole: ChatMessage.Role? = null
        val currentText = StringBuilder()

        text.lines().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("User:") -> {
                    if (currentRole != null && currentText.isNotEmpty()) {
                        _messages.add(ChatMessage(currentRole, currentText.toString().trim()))
                    }
                    currentRole = ChatMessage.Role.User
                    currentText.clear()
                    currentText.append(trimmed.removePrefix("User:").trim())
                }
                trimmed.startsWith("Assistant:") -> {
                    if (currentRole != null && currentText.isNotEmpty()) {
                        _messages.add(ChatMessage(currentRole, currentText.toString().trim()))
                    }
                    currentRole = ChatMessage.Role.Assistant
                    currentText.clear()
                    currentText.append(trimmed.removePrefix("Assistant:").trim())
                }
                trimmed.startsWith("System:") -> {
                    if (currentRole != null && currentText.isNotEmpty()) {
                        _messages.add(ChatMessage(currentRole, currentText.toString().trim()))
                    }
                    currentRole = ChatMessage.Role.System
                    currentText.clear()
                    currentText.append(trimmed.removePrefix("System:").trim())
                }
                trimmed.isNotBlank() && currentRole != null -> {
                    if (currentText.isNotEmpty()) currentText.append(' ')
                    currentText.append(trimmed)
                }
            }
        }
        if (currentRole != null && currentText.isNotEmpty()) {
            _messages.add(ChatMessage(currentRole, currentText.toString().trim()))
        }
        _uiState.value = _uiState.value.copy(messages = _messages.toList())
    }

    //----- Chat ops
    fun updateModelConfig(config: ModelConfig) {
        if (_uiState.value.selectedModelConfig != config) {
            _uiState.value = _uiState.value.copy(selectedModelConfig = config)
            Log.d("MainViewModel", "Model config updated: ${config.displayName}")
        }
    }
    fun updateBackend(backend: BackendType) {
        val previousBackend = _uiState.value.selectedBackend
        if (previousBackend != backend) {
            _uiState.value = _uiState.value.copy(selectedBackend = backend)
            if (llmModule != null) {
                showToast("Backend changed. Please reload the model.")
                llmModule = null
                _uiState.value = _uiState.value.copy(isModelLoaded = false)
            }
            Log.d("MainViewModel", "Backend selected: ${BackendType.getDisplayName(backend)}")
        }
    }
    fun updateSystemPrompt(prompt: String) {
        val newPrompt = prompt.ifEmpty { ChatFormatter.DEFAULT_SYS_PROMPT }
        _uiState.value = _uiState.value.copy(userSystemPrompt = newPrompt)
        shouldAddSysPrompt = true
        Log.d("MainViewModel", "System prompt updated: $newPrompt")
        showToast("System prompt updated!")
    }
    fun loadModel() {
        if (_uiState.value.isLoading) return
        _uiState.value = _uiState.value.copy(isLoading = true, isModelLoaded = false)
        executorchExecutor.submit {
            try {
                val modelsDir = AssetMover.getModelsDirectory(context)
                val config = _uiState.value.selectedModelConfig
                val modelPath = File(modelsDir, config.modelFileName).absolutePath
                val tokenizerPath = File(modelsDir, config.tokenizerFileName).absolutePath


                //check if in external storage
                if (!File(modelPath).exists()) {
                    try {
                        AssetMover.copyAssetToModelsDir(
                            context,
                            config.modelFileName
                        )
                        Log.d("MainActivity", "✓ Model copied from assets")
                    }catch (e: Exception) {
                        Log.d("MainActivity", "Can't copy model $modelPath ")
                    }
                } else {
                    Log.d("MainActivity", "Model already exists: $modelPath")
                }
                if (!File(tokenizerPath).exists()) {
                    AssetMover.copyAssetToModelsDir(context, config.tokenizerFileName)
                } else {
                    Log.d("MainActivity", "Tokenizer already exists: $tokenizerPath")
                }
                // Verify files exist before loading
                if (!File(modelPath).exists()) {
                    throw Exception("Model file not found: ${config.modelFileName}")
                }
                if (!File(tokenizerPath).exists()) {
                    throw Exception("Tokenizer file not found: ${config.tokenizerFileName}")
                }
                Log.d("MainActivity", "Loading model: ${File(modelPath).length() / (1024*1024)}MB")
                Log.d("MainActivity", "Loading tokenizer: ${File(tokenizerPath).length() / 1024}KB")

                val module = LlmModule(
                    config.modelType.getModelType(),
                    modelPath,
                    tokenizerPath,
                    config.temperature// temperature
                )
                val loadResult = module.load()
                if (loadResult != 0) throw Exception("Model load failed with code: $loadResult")

                if (config.modelType == ModelType.LLAVA) {
                    val presetPrompt = ChatFormatter.getLlavaPresetPrompt()
                    module.prefillPrompt(presetPrompt)
                    Log.d("MainActivity", "Prefilled LLaVA preset prompt")
                    needsPrefill = false
                } else {
                    needsPrefill = true
                }
                llmModule = module
                postUiUpdate {
                    _uiState.value = _uiState.value.copy(
                        isModelLoaded = true,
                        isLoading = false
                    )
                    showToast("Model Loaded!!")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Model load failed", e)
                showToast("Model load unsuccessful!!: ${e.message}")
            }
        }
    }

     fun unloadModel() {

        val module = llmModule
        if (module == null) {
            showToast("Please load model first!!!")
            return
        }
        try {
            executorchExecutor.submit {
                module.resetNative()
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error resetting model", e)
        } finally {
            llmModule = null
            postUiUpdate {
                _uiState.value = _uiState.value.copy(isModelLoaded = false)
                showToast("Model Unloaded!!")
            }
            Log.d("MainViewModel", "--Model Unloaded!!--")
        }
    }

     fun startNewChat() {
        val module = llmModule
        if (module == null) {
            showToast("Please load model first!!!")
            return
        }

        clearMessages()
         _uiState.value = _uiState.value.copy(
             currentChatId = null,
             isLoading = true
         )
        executorchExecutor.submit{
            try {
                module.resetContext()
                module.prefillPrompt("<|begin_of_text|>")
                needsPrefill = false
                postUiUpdate {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    showToast("New converstation started!!")
                    Log.d("MainActivity", "--New chat started!!--")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to reset model", e)
                postUiUpdate {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    showToast("Reset Failed: ${e.message}")
                }
            }
        }
    }

    fun stopGen() {
        executorchExecutor.submit {
            llmModule?.stop()
        }
        _uiState.value = _uiState.value.copy(isGenerating = false)
        showToast("Generation Stopped!!")
        Log.d("MainActivity", "Generation stopped!")
    }

    fun prefillImage(imageUri: Uri, contentResolver: ContentResolver) {
        val module = llmModule ?: run {
            showToast("Please load model first!!!")
            return
        }
        showToast("Processing Image...")

        executorchExecutor.submit {
            try {
                val etImage = ETImage(contentResolver, imageUri, 336)

                // prefill
                llmModule?.prefillImages(
                    etImage.getInts(), //llava uses ints
                    etImage.width,
                    etImage.height,
                    3
                )
                Log.d("MainActivity", "Image prefilled for llava")
                postUiUpdate {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    showToast("Image processed")
                }
            } catch (e: Exception) {
                postUiUpdate {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    showToast("Image prefill failed: ${e.message}")
                }
            }
        }
    }

    fun sendMessage(userMessage: String, hasImage: Boolean = false) {
        if (userMessage.isEmpty()) return

        val module = llmModule
        if (module == null) {
            showToast("Please load model first!!!")
            return
        }
        //Check if vision
        val modelType = _uiState.value.selectedModelConfig.modelType
        val userSystemPrompt = _uiState.value.userSystemPrompt

        val prompt = when {
            modelType == ModelType.LLAVA && hasImage -> {
                needsPrefill = false
                shouldAddSysPrompt = false
                ChatFormatter.getLlavaFirstTurnUserPrompt().replace(ChatFormatter.USER_PLACEHOLDER, userMessage)
            }
            else -> {
                val sysPrompt = if (shouldAddSysPrompt && modelType != ModelType.LLAVA) {
                    showToast("Adding system prompt!")
                    ChatFormatter.buildSystemPromptTemplate(
                        modelType).replace(ChatFormatter.SYSTEM_PLACEHOLDER, userSystemPrompt)
                } else {
                    ""
                }
                shouldAddSysPrompt = false
                needsPrefill = false
                sysPrompt + ChatFormatter.buildDeltaFromUser(modelType, userMessage)
            }
        }
        // add user message
        appendUser(userMessage)
        //placeholder for assistamt
        _messages.add(ChatMessage(ChatMessage.Role.Assistant, ""))
        val assMsgIndex = _messages.lastIndex
        _uiState.value = _uiState.value.copy(
            _messages.toList(),
            isGenerating = true,
            isLoading = true
        )
        val responseBuilder = StringBuilder()

        executorchExecutor.submit {
            try {
                //Handle both image and non-image pipeline
                Log.d("PROMPT", "Sending prompt:\n$prompt")

                val cb = object : LlmCallback {
                    override fun onResult(s: String) {
                        if (s == ChatFormatter.getStopToken(modelType)) {
                            if (modelType == ModelType.LLAVA) {
                                module.stop()
                            }
                            return
                        }
                        val clean = ChatFormatter.sanitizeChunk(s)
                        if (clean.isEmpty()) return
                        responseBuilder.append(clean)

                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastUiUpdateMs < 80L) return
                        lastUiUpdateMs = now
                        val partial = responseBuilder.toString()
                        postUiUpdate {
                            if (assMsgIndex < _messages.size) {
                                _messages[assMsgIndex] = ChatMessage(
                                    ChatMessage.Role.Assistant,
                                    partial
                                )
                                _uiState.value = _uiState.value.copy(messages = _messages.toList())
                            }
                        }
                    }
                    override fun onStats(stats: String) {
                        try {
                            val j = JSONObject(stats)

                            val promptTokens = j.optLong("prompt_tokens")
                            val numGeneratedTokens = j.optLong("generated_tokens")
                            val inferenceStartMs = j.optLong("inference_start_ms")
                            val inferenceEndMs = j.optLong("inference_end_ms")
                            val promptEvalEndMs = j.optLong("prompt_eval_end_ms")
                            val firstTokenMs = j.optLong("first_token_ms")

                            //Decode: Tokens/s
                            val decodeMs = (inferenceEndMs - promptEvalEndMs).toDouble()
                            val decodeTps = (numGeneratedTokens.toDouble() / decodeMs) * 1000

                            //TTFT: first token time from inference start
                            val ttftMs = firstTokenMs - inferenceStartMs
                            val ttftSec = ttftMs / 1000.0

                            //Prefill: Tokens/s
                            val prefillMs = (promptEvalEndMs - inferenceStartMs).toDouble()
                            val prefillTps = (promptTokens.toDouble() / prefillMs) * 1000

                            val line = buildString {
                                append("$numGeneratedTokens tok . ")
                                append("${String.format("%.1f", decodeTps)} tok/s")
                                append(" · ${String.format("%.1f", ttftSec)}s TTFT")
                                append(" · ${String.format("%.1f", prefillTps)} prefill tok/s")
                            }
                            // Optional: GPU stats for Vulkan comparison
                            val gpuPeak = j.optDouble("gpu_peak_usage_mb", -1.0)
                            if (gpuPeak >= 0) {
                                line + " · GPU: ${String.format("%.0f", gpuPeak)}MB"
                            }
                            postUiUpdate {
                                _uiState.value = _uiState.value.copy(
                                    statsText = line,
                                    showStats = true
                                )
                            }
                            Log.d("LLM-Stats", line)
                        } catch (t: Throwable) {
                            Log.e("LLM-STATS", "Failed to parse stats: ${t.message}")
                        }
                    }
                }
                Log.d("SEND", "Calling generate with continuous prompt")
                module.generate(prompt, 768.toInt(), cb, false)

                val finalText = responseBuilder.toString().trim()
                Log.d("GENERATE", "Generate returned: '$finalText'")  // ✅ Debug log

                postUiUpdate {
                    if (finalText.isNotEmpty()) {
                        _messages[assMsgIndex] = ChatMessage(
                            ChatMessage.Role.Assistant,
                            finalText
                        )
                    } else {
                        //messages.removeAt(assistantMsgIndex)
                        _messages.removeAt(assMsgIndex)
                        showToast("No response generated")
                    }
                    _uiState.value = _uiState.value.copy(
                        messages = _messages.toList(),
                        isGenerating = false,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Generation failed", e)
                postUiUpdate {
                    if (assMsgIndex < _messages.size) {
                        _messages.removeAt(assMsgIndex)
                    }
                    _uiState.value = _uiState.value.copy(
                        messages = _messages.toList(),
                        isGenerating = false,
                        isLoading = false
                    )
                    showToast("Generation failed: ${e.message}")
                }
            }
        }
    }

    fun loadWhisperIfNeeded(onReady: () -> Unit) {
        if (_uiState.value.isWhisperLoaded) {
            onReady()
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true)
        showToast("Loading Whisper model...")

        executorchExecutor.submit {
            try {
                val success = loadWhisperModules()
                postUiUpdate {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    if (success) {
                        showToast("Whisper loaded. Tap mic to record!")
                        onReady()
                    } else {
                        showToast("Failed to load Whisper modules")
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to load Whisper", e)
                postUiUpdate {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    showToast("Whisper load error: ${e.message}")
                }
            }
        }
    }

    private fun loadWhisperModules(): Boolean {
        try {
            val modelsDir = AssetMover.getModelsDirectory(context)

            val preprocPath = File(modelsDir, WHISPER_PREPROC_ASSET).absolutePath
            val modelPath = File(modelsDir, WHISPER_MODEL_ASSET).absolutePath
            val tokenizerPath = File(modelsDir, WHISPER_TOKENIZER_ASSET).absolutePath

            // Copy preprocessor if needed
            if (!File(preprocPath).exists()) {
                postToast("Copying Whisper preprocessor from assets...")
                try {
                    AssetMover.copyAssetToModelsDir(context, WHISPER_PREPROC_ASSET)
                    Log.d("ASR", "✓ Preprocessor copied from assets")
                } catch (e: Exception) {
                    Log.e("ASR", "Failed to copy preprocessor: ${e.message}")
                    return false
                }
            }

            // Copy model if needed
            if (!File(modelPath).exists()) {
                postToast("Copying Whisper model from assets...")
                try {
                    AssetMover.copyAssetToModelsDir(context, WHISPER_MODEL_ASSET)
                    Log.d("ASR", "✓ Whisper model copied from assets")
                } catch (e: Exception) {
                    Log.e("ASR", "Failed to copy model: ${e.message}")
                    return false
                }
            }

            // Copy tokenizer if needed
            if (!File(tokenizerPath).exists()) {
                postToast("Copying Whisper tokenizer from assets...")
                try {
                    AssetMover.copyAssetToModelsDir(context, WHISPER_TOKENIZER_ASSET)
                    Log.d("ASR", "✓ Whisper tokenizer copied from assets")
                } catch (e: Exception) {
                    Log.e("ASR", "Failed to copy tokenizer: ${e.message}")
                    return false
                }
            }

            // Verify files
            val preprocFile = File(preprocPath)
            val modelFile = File(modelPath)
            val tokenizerFile = File(tokenizerPath)

            if (!preprocFile.exists() || preprocFile.length() == 0L) {
                Log.e("ASR", "Preprocessor file missing or empty")
                return false
            }
            if (!modelFile.exists() || modelFile.length() == 0L) {
                Log.e("ASR", "Model file missing or empty")
                return false
            }
            if (!tokenizerFile.exists() || tokenizerFile.length() == 0L) {
                Log.e("ASR", "Tokenizer file missing or empty")
                return false
            }

            Log.d("ASR", "Preprocessor: ${preprocFile.length()} bytes")
            Log.d("ASR", "Model: ${modelFile.length()} bytes")
            Log.d("ASR", "Tokenizer: ${tokenizerFile.length()} bytes")

            val asr = AsrModule(modelPath, tokenizerPath, null, preprocPath)
            val loadResult = asr.load()
            if (loadResult != 0) {
                Log.e("ASR", "AsrModule.load() failed: $loadResult")
                return false
            }

            whisperAsr = asr
            _uiState.value = _uiState.value.copy(isWhisperLoaded = true)
            Log.d("ASR", "Whisper modules loaded successfully")
            return true
        } catch (t: Throwable) {
            Log.e("ASR", "Failed to load Whisper modules", t)
            return false
        }
    }
    //asr
    fun transcribeAudio(wavFile: File, onComplete: (String) -> Unit) {
        val asr = whisperAsr ?: run {
            showToast("Whisper not loaded!")
            onComplete("")
            return
        }

        _uiState.value = _uiState.value.copy(isTranscribing = true, isLoading = true)

        executorchExecutor.submit {
            try {
                val asrRaw = StringBuilder()
                val cb = object : AsrCallback {
                    override fun onToken(token: String) {
                        asrRaw.append(token)
                    }
                }

                val textRaw = asr.transcribe(wavFile.absolutePath, callback = cb)
                val transcription = cleanWhisperText(textRaw)
                Log.d("ASR", "Transcription: $transcription")

                postUiUpdate {
                    _uiState.value = _uiState.value.copy(
                        isTranscribing = false,
                        isLoading = false,
                        transcribedText = transcription
                    )
                    onComplete(transcription)
                }
            } catch (t: Throwable) {
                Log.e("ASR", "Whisper transcription failed", t)
                postUiUpdate {
                    _uiState.value = _uiState.value.copy(
                        isTranscribing = false,
                        isLoading = false
                    )
                    showToast("Whisper error: ${t.message}")
                    onComplete("")
                }
            } finally {
                try {
                    wavFile.delete()
                    Log.d("ASR", "Temp WAV file deleted")
                } catch (_: Throwable) {}
            }
        }
    }

    private fun cleanWhisperText(raw: String): String {
        var s = raw.replace(Regex("<\\|.*?\\|>"), "")
        s = s.replace("<s>", "").replace("</s>", "")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    fun clearTranscribedText() {
        _uiState.value = _uiState.value.copy(transcribedText = null)
    }

    //--- Chat storage
    fun loadSavedChat(thread: ChatThread) {

        val module = llmModule
        if (module == null) {
            val msg = "Please load model first!!"
            showToast(msg)
            return
        }
        Log.d("MainActivity", "--Loading saved chat!!--")
        val content = ChatStore.load(thread)
        resetFromTranscript(content)
        _uiState.value = _uiState.value.copy(
            currentChatId = thread.id,
            isLoading = true
        )

        showToast("Chat loaded")
        executorchExecutor.submit{
            try {
                module.resetContext()
                module.prefillPrompt(buildFullPrompt())
                needsPrefill = false
                postUiUpdate {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    showToast("Chat loaded successfully")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Prefill failed", e)
                postUiUpdate {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    showToast("Prefill failed: ${e.message}")
                }
            }
        }
    }

    fun saveCurrentChat(): Boolean {
        val txt = fullTranscript()
        if (txt.isBlank()) return false
        val currentId = _uiState.value.currentChatId

        if (currentId != null) {
            ChatStore.update(context, currentId, txt)
            showToast("Chat updated")
        } else {
            val newThread = ChatStore.save(context, txt)
            _uiState.value = _uiState.value.copy(currentChatId = newThread.id)
            showToast("Chat saved")
        }
        return true
    }
    fun deleteSavedChat(thread: ChatThread){
        ChatStore.delete(context, thread.id)

        if (_uiState.value.currentChatId == thread.id) {
            clearMessages()
            _uiState.value = _uiState.value.copy(currentChatId = null)
        }
        showToast("Chat deleted")
    }

    fun getChatList(): List<ChatThread> = ChatStore.list(context)

    fun isModelLoaded(): Boolean = llmModule != null
    fun isWhisperLoaded(): Boolean = whisperAsr != null

    fun hideStats() {
        _uiState.value = _uiState.value.copy(showStats = false)
    }

    fun clearToast() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
    }

    private fun showToast(message: String) {
        _uiState.value = _uiState.value.copy(toastMessage = message)
    }

    private fun postToast(message: String) {
        mainHandler.post { showToast(message) }
    }

    private fun postUiUpdate(action: () -> Unit) {
        mainHandler.post(action)
    }

    override fun onCleared() {
        super.onCleared()

        // Clean up on executor thread
        executorchExecutor.submit {
            try {
                llmModule?.stop()
                llmModule?.resetNative()
                Log.d("MainViewModel", "LLM module cleaned up")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error cleaning up LLM", e)
            }

            try {
                whisperAsr?.close()
                Log.d("MainViewModel", "Whisper module cleaned up")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error cleaning up Whisper", e)
            }
        }

        llmModule = null
        whisperAsr = null

        // Shutdown executor
        try {
            executorchExecutor.shutdown()
            if (!executorchExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                executorchExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executorchExecutor.shutdownNow()
        }

        Log.d("MainViewModel", "ViewModel cleared, all resources cleaned up")
    }
}


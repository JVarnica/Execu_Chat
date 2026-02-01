package com.example.execu_chat

import android.Manifest
import android.content.Context
import android.media.AudioRecord
import android.media.AudioFormat
import android.media.MediaRecorder
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.Handler
import android.widget.*
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.core.view.GravityCompat
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmModule
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.withContext
import android.util.Log
import org.json.JSONObject
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import org.pytorch.executorch.EValue
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.NonReadableChannelException


class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var loadBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var menuBtn: ImageButton
    private lateinit var newChatBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var statsText: TextView
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var input: EditText
    private lateinit var sysPrompt: EditText
    private lateinit var sendBtn: ImageButton

    private lateinit var micBtn: ImageButton
    private lateinit var chatList: RecyclerView
    private lateinit var modelSpinner: Spinner
    private lateinit var attachBtn: ImageButton
    private lateinit var backendSpinner: Spinner
    private val messages = mutableListOf<Message>()
    private var selectedBackend: BackendType = BackendType.XNNPACK

    @Volatile private var llmModule: LlmModule? = null
    @Volatile private var lastUiUpdateMs = 0L
    private var userSystemPrompt: String = ChatFormatter.DEFAULT_SYS_PROMPT
    private var shouldAddSysPrompt = true
    private var modelLoaded = false
    private var isGenerating = false
    private var needsPrefill = true
    private var currentChatId: String? = null
    private var selectedImageUri: Uri? = null

    private lateinit var chatAdapter: ChatAdapter

    private lateinit var session: ChatSession
    //asr whisper
    private var whisperPreproc: Module? = null
    private var whisperModel: Module? = null

    private var whisperLoaded: Boolean = false
    // Audio recording for Whisper
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private val audioHandler = Handler(Looper.getMainLooper())
    private val recordDurationMs = 5000L

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val audioBufferSize by lazy {
        AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    }
    private val pcmCollector = ByteArrayOutputStream()

    private val WHISPER_PREPROC_ASSET = "whisper_preprocess.pte"
    private val WHISPER_MODEL_ASSET = "whisper-small-fp32.pte"
    private val WHISPER_TOKENIZER_ASSET = "wtokenizer.json"

    private var currModelConfig: ModelConfig = ModelConfigs.ALL.first { it.modelType == ModelType.LLAMA_3 }

    // Permission launcher
    private val reqRecordAudio = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onMicClicked()
        } else Toast.makeText(
            this,
            "Microphone permission required for voice input",
            Toast.LENGTH_SHORT
        ).show()
    }
    private val reqPickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            Log.d("Main Activity", "Image Selected: $it")
            prefillImage()

        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_menu)
        initializeViews()
        setupListeners()
        loadChatList()
        session = ChatSession(currModelConfig.modelType)
    }
    private fun initializeViews() {

        // Drawer + UI refs
        drawerLayout = findViewById(R.id.drawerLayout)
        loadBtn = findViewById(R.id.loadBtn)
        progress = findViewById(R.id.progress)

        menuBtn = findViewById(R.id.menuBtn)
        newChatBtn = findViewById(R.id.newChatBtn)
        saveBtn = findViewById(R.id.saveBtn)
        messagesRecyclerView = findViewById(R.id.messagesRecyclerView)
        statsText = findViewById(R.id.statsText)

        messageAdapter = MessageAdapter()
        messagesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = messageAdapter
        }
        sendBtn = findViewById(R.id.sendBtn)
        chatList = findViewById(R.id.chatList)
        micBtn = findViewById(R.id.micBtn)
        attachBtn = findViewById(R.id.galleryBtn)
        input = findViewById(R.id.input)
        backendSpinner = findViewById(R.id.backendSpinner)
        modelSpinner = findViewById(R.id.modelSpinner)
        sysPrompt = findViewById(R.id.sysBtn)

        // Initially disable chat list
        chatList.isEnabled = false
        chatList.alpha = 0.5f
        chatList.layoutManager = LinearLayoutManager(this)

        setupBackendSpinner()
        setupModelSpinner()
    }
    private fun setupListeners() {
        // Drawer toggle
        menuBtn.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        loadBtn.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            if (!modelLoaded) {
                loadModel()
            } else {
                unloadModel()
            }
        }
        // New Chat
        newChatBtn.setOnClickListener {
            startNewChat()
        }

        // Save chat
        saveBtn.setOnClickListener {
            saveCurrentChat()
        }
        // System prompt - updates when user presses "Done" on keyboard
        sysPrompt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                systemPromptChange()
                // Hide keyboard
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(sysPrompt.windowToken, 0)
                true
            } else {
                false
            }
        }
        // Also update when user taps away (loses focus)
        sysPrompt.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                systemPromptChange()

            }
        }

        // RecyclerView for saved chats -open saved chat
        chatAdapter = ChatAdapter(
            { thread -> loadSavedChat(thread) },
            onDelete = { thread -> deleteSavedChat(thread) }
        )
        chatList.adapter = chatAdapter

        micBtn.setOnClickListener {
            onMicClicked()
        }
        // Send button
        sendBtn.setOnClickListener {
            if (isGenerating) {
                stopGen()
            } else {
                sendMessage()
            }
        }

        attachBtn.setOnClickListener {
            if (currModelConfig.modelType.isMultiModal()) {
                reqPickImage.launch("image/*")
            } else {
                toast("Llama only text. Use Llava!")
            }
        }
    }
    private fun setupBackendSpinner() {
        // Choose backend xnnpack or vulkan
        val backendNames = listOf(
            BackendType.getDisplayName(BackendType.XNNPACK),
            BackendType.getDisplayName(BackendType.VULKAN),
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, backendNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        backendSpinner.adapter = adapter

        backendSpinner.setSelection(0)
        backendSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val previousBackend = selectedBackend
                selectedBackend = when (position) {
                    0 -> BackendType.XNNPACK
                    1 -> BackendType.VULKAN
                    else -> BackendType.XNNPACK
                }

                // If backend changed and model is loaded, warn user
                if (previousBackend != selectedBackend && llmModule != null) {
                    toast("Backend changed. Please reload the model.")
                    // Optionally unload the current model
                    llmModule = null
                    gateUi(loaded = false, busy = false)
                }

                Log.d("MainActivity", "Backend selected: ${BackendType.getDisplayName(selectedBackend)}")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Keep current selection
            }
        }
    }
    private fun setupModelSpinner() {
        // chnage models logic
        val modelNames = ModelConfigs.ALL.map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modelNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modelSpinner.adapter = adapter

        val defaultIndex = ModelConfigs.ALL.indexOfFirst { it.modelType == ModelType.LLAMA_3 }
        if (defaultIndex >= 0) {
            modelSpinner.setSelection(defaultIndex)
        }
        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currModelConfig = ModelConfigs.ALL[position]
                session = ChatSession(currModelConfig.modelType)
                Log.d("MainActivity", "Model selected: ${currModelConfig.displayName}")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Keep current selection
                Log.d("MainActivity", "Spinner selection cleared, keeping: ${currModelConfig.displayName}")
            }
        }
    }
    private fun loadModel() {
        gateUi(loaded = false, busy = true)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val modelsDir = AssetMover.getModelsDirectory(this@MainActivity)
                val modelPath = File(modelsDir, currModelConfig.modelFileName).absolutePath
                val tokenizerPath = File(modelsDir, currModelConfig.tokenizerFileName).absolutePath


                //check if in external storage
                if (!File(modelPath).exists()) {
                    withContext(Dispatchers.Main) {
                        toast("Copying ${currModelConfig.modelFileName} from assets")
                    }
                    try {
                        AssetMover.copyAssetToModelsDir(
                            this@MainActivity,
                            currModelConfig.modelFileName
                        )
                        Log.d("MainActivity", "✓ Model copied from assets")
                    }catch (e: Exception) {
                        Log.d("MainActivity", "Can't copy model $modelPath ")
                    }
                } else {
                    Log.d("MainActivity", "Model already exists: $modelPath")
                }
                if (!File(tokenizerPath).exists()) {
                    withContext(Dispatchers.Main) {
                        toast("Copying tokenizer from assets...")
                    }
                    AssetMover.copyAssetToModelsDir(this@MainActivity, currModelConfig.tokenizerFileName)
                } else {
                    Log.d("MainActivity", "Tokenizer already exists: $tokenizerPath")
                }
                // Verify files exist before loading
                if (!File(modelPath).exists()) {
                    throw Exception("Model file not found: ${currModelConfig.modelFileName}")
                }
                if (!File(tokenizerPath).exists()) {
                    throw Exception("Tokenizer file not found: ${currModelConfig.tokenizerFileName}")
                }
                Log.d("MainActivity", "Loading model: ${File(modelPath).length() / (1024*1024)}MB")
                Log.d("MainActivity", "Loading tokenizer: ${File(tokenizerPath).length() / 1024}KB")

                withContext(Dispatchers.Main) {
                    toast("Loading ${currModelConfig.displayName}...")
                }
                val module = LlmModule(
                    currModelConfig.modelType.getModelType(),
                    modelPath,
                    tokenizerPath,
                    currModelConfig.temperature// temperature
                )
                val loadResult = module.load()
                if (loadResult != 0) throw Exception("Model load failed with code: $loadResult")

                if (currModelConfig.modelType == ModelType.LLAVA) {
                    val presetPrompt = ChatFormatter.getLlavaPresetPrompt()
                    module.prefillPrompt(presetPrompt)
                    Log.d("MainActivity", "Prefilled LLaVA preset prompt")
                    needsPrefill = false
                } else {
                    needsPrefill = true
                }
                llmModule = module

                withContext(Dispatchers.Main) {
                    toast("Model loaded successfully")
                    modelLoaded = true
                    gateUi(loaded = true, busy = false)
                    chatList.isEnabled = true
                    chatList.alpha = 1f
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Model load failed", e)
                withContext(Dispatchers.Main) {
                    toast("Failed to load model: ${e.message}")
                    gateUi(loaded = false, busy = false)
                }
            }
        }
    }
    private fun unloadModel() {
        gateUi(loaded = true, busy = false)
        val module = llmModule
        if (module == null) {
            toast("Please load model first!!!")
            return
        }
        try {
            lifecycleScope.launch(Dispatchers.IO) {
                module.resetNative()
            }
        } finally {
            toast("Model Unloaded!!")
            Log.d("MainActivity", "--Model Unloaded!!--")
            modelLoaded = false
            gateUi(loaded = false, busy = false)
        }
    }
    private fun startNewChat() {
        val module = llmModule
        if (module == null) {
            toast("Please load model first!!!")
            return
        }

        session.clear()
        messages.clear()
        messageAdapter.setItems(messages)
        currentChatId = null
        drawerLayout.closeDrawer(GravityCompat.START)

        gateUi(loaded = false, busy = true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                module.resetContext()
                module.prefillPrompt("<|begin_of_text|>")
                needsPrefill = false
                withContext(Dispatchers.Main) {
                    gateUi(loaded = true, busy = false)
                    toast("New converstation started!!")
                    Log.d("MainActivity", "--New chat started!!--")
                    }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to reset model", e)
                withContext(Dispatchers.Main) {
                    gateUi(loaded = true, busy = false)
                    toast("Reset Failed: ${e.message}")
                }
            }
        }
    }
    private fun stopGen() {
        llmModule?.stop()
        updateSendBtnState(false)
        toast("Generation Stopped!!")
        Log.d("MainActivity", "Generation stopped!")
    }
    private fun prefillImage() {
        toast("Processing Image...")
        val imageUri = selectedImageUri!!
        gateUi(loaded = true, busy = true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val etImage = ETImage(contentResolver, imageUri, 336)

                // prefill
                llmModule?.prefillImages(
                    etImage.getInts(), //llava uses ints
                    etImage.width,
                    etImage.height,
                    3
                )
                selectedImageUri = null
                Log.d("MainActivity", "Image prefilled for ${currModelConfig.modelType}")
                withContext(Dispatchers.Main) {
                    toast("Processing Image!!")
                    gateUi(loaded = true, busy = false)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast("Image prefill failed: ${e.message}")
                    gateUi(loaded = true, busy = false)
                }
            }
        }
    }
    private fun sendMessage() {
        val msg = input.text.toString().trim()
        if (msg.isEmpty() || !sendBtn.isEnabled) return

        val module = llmModule
        if (module == null) {
            toast("Please load model first!!!")
            return
        }
        //Check if vision
        val modelType = currModelConfig.modelType
        val hasImage = selectedImageUri != null

        val prompt = when {
            modelType == ModelType.LLAVA && hasImage -> {
                needsPrefill = false
                shouldAddSysPrompt = false
                ChatFormatter.getLlavaFirstTurnUserPrompt().replace(ChatFormatter.USER_PLACEHOLDER, msg)
            }
            else -> {
                val sysPrompt = if (shouldAddSysPrompt && modelType != ModelType.LLAVA) {
                    toast("Adding system prompt!")
                    ChatFormatter.buildSystemPromptTemplate(
                        modelType).replace(ChatFormatter.SYSTEM_PLACEHOLDER, userSystemPrompt)
                } else {
                    ""
                }
                shouldAddSysPrompt = false
                needsPrefill = false
                sysPrompt + ChatFormatter.buildDeltaFromUser(modelType, msg)
            }
        }

        input.text.clear()
        // update session + user bubble placeholder for assis response
        session.appendUser(msg)
        //messages.add(Message(msg, isUser = true))
        messageAdapter.addItem(Message(msg, isUser = true))


        val assistantMsgIndex = messageAdapter.addItemAndReturnIndex(Message("", isUser = false))
        messagesRecyclerView.scrollToPosition(assistantMsgIndex)

        updateSendBtnState(true)
        val responseBuilder = StringBuilder()
        gateUi(loaded = true, busy = true)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                //Handle both image and non-image pipeline

                Log.d("PROMPT", "Sending prompt:\n$prompt")
                Log.d("PROMPT", "Full session:\n${session.fullPrompt()}")

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
                        runOnUiThread {
                            //messages[assistantMsgIndex] = Message(partial, isUser = false)
                            messageAdapter.updateItem(
                                assistantMsgIndex,
                                Message(partial, isUser = false)
                            )
                            messagesRecyclerView.scrollToPosition(assistantMsgIndex)
                        }
                    }
                    override fun onStats(stats: String) {
                        try {
                            val j = JSONObject(stats)
                            var tps: Double = 0.0

                            val numGeneratedTokens = j.optLong("generated_tokens")
                            val inferenceEndMs = j.optLong("inference_end_ms")
                            val promptEvalEndMs = j.optLong("prompt_eval_end_ms")
                            val decodeTime = (inferenceEndMs - promptEvalEndMs).toDouble()
                            tps = (numGeneratedTokens.toDouble() / decodeTime) * 1000

                            val line = buildString {
                                append("$numGeneratedTokens tok . ")
                                append(String.format("%.2f tok/s", tps))
                                append(" . decode ${decodeTime}ms")
                            }
                            runOnUiThread {
                                statsText.text = line
                                statsText.visibility = View.VISIBLE
                                /*statsText.postDelayed({
                                        statsText.visibility = View.GONE
                                    }, 3000)*/
                            }
                            Log.d("LLM-Stats", line)
                        } catch (t: Throwable) {
                            Log.w("LLM-STATS", "Failed to parse stats: ${t.message}")
                        }
                    }
                }
                Log.d("SEND", "Calling generate with continuous prompt")
                module.generate(prompt, 768.toInt(), cb, false)

                val finalText = responseBuilder.toString().trim()
                Log.d("GENERATE", "Generate returned: '$finalText'")  // ✅ Debug log

                withContext(Dispatchers.Main) {
                    updateSendBtnState(false)
                    gateUi(loaded = true, busy = false)
                    if (finalText.isNotEmpty()) {
                        session.appendAssistant(finalText)
                        Log.d("REPLY", "Final text: '$finalText'")
                        //messages[assistantMsgIndex] = Message(finalText, isUser = false)
                        messageAdapter.updateItem(assistantMsgIndex, Message(finalText, isUser = false))
                        messagesRecyclerView.scrollToPosition(assistantMsgIndex)
                    } else {
                        //messages.removeAt(assistantMsgIndex)
                        messageAdapter.removeAt(assistantMsgIndex)
                        toast("No response generated")
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Generation failed", e)
                withContext(Dispatchers.Main) {
                    updateSendBtnState(false)
                    gateUi(loaded = true, busy = false)
                    toast("Generation failed: ${e.message}")
                    // Remove the empty assistant message if generation fails
                    if (messages.size > assistantMsgIndex) {
                        messages.removeAt(assistantMsgIndex)
                        messageAdapter.removeAt(assistantMsgIndex)
                    }
                }
            }
        }
    }
    private fun updateSendBtnState(generating: Boolean) {
        isGenerating = generating
        runOnUiThread {
            if (generating) {
                sendBtn.setImageResource(R.drawable.outline_block_24)
                sendBtn.contentDescription = "Stop"
            } else {
                sendBtn.setImageResource(R.drawable.baseline_send_24)
                sendBtn.contentDescription = getString(R.string.send_button)
            }
        }
    }
    private fun systemPromptChange() {
        val newSysPrompt = sysPrompt.text.toString().trim().ifEmpty { ChatFormatter.DEFAULT_SYS_PROMPT }
        userSystemPrompt = newSysPrompt
        shouldAddSysPrompt = true
        Log.d("SysPrompt", "System prompt updated: $newSysPrompt")
        toast("System prompt updated!")
    }
    private fun loadSavedChat(thread: ChatThread) {
        Log.d("MainActivity", "--Loading saved chat!!--")
        val module = llmModule
        if (module == null) {
            val msg = "Please load model first!!"
            toast(msg)
            drawerLayout.closeDrawer(GravityCompat.START)
            return
        }
        val content = ChatStore.load(thread)
        session.resetFromTranscript(content)
        currentChatId = thread.id
        // transcript to message bubbles/UI
        messages.clear()
        for (turn in session.turns) {
            messages.add(
                Message(
                    text = turn.text,
                    isUser = turn.role == Turn.Role.User
                )
            )
        }
        messageAdapter.setItems(messages)
        messagesRecyclerView.scrollToPosition(maxOf(0, messages.size - 1))

        toast("Chat loaded")
        drawerLayout.closeDrawer(GravityCompat.START)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                module.resetContext()
                module.prefillPrompt(session.fullPrompt())
                needsPrefill = false
                withContext(Dispatchers.Main) {
                    toast("Chat loaded successfully")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Prefill failed", e)
                withContext(Dispatchers.Main) {
                    toast("Prefill failed: ${e.message}")
                }
            }
        }
    }
    private fun loadChatList() {
        val threads = ChatStore.list(this)
        chatAdapter.submitList(threads)
    }
    private fun saveCurrentChat(){
        val txt = session.fullTranscript()
        if (txt.isNotBlank()) {
            if (currentChatId != null) {
                ChatStore.update(this, currentChatId!!, txt)
                Toast.makeText(this, "Chat updated", Toast.LENGTH_SHORT).show()
            } else {
                val newThread = ChatStore.save(this, txt)
                currentChatId = newThread.id
                Toast.makeText(this, "Chat saved", Toast.LENGTH_SHORT).show()
            }
            loadChatList()
        }
        drawerLayout.closeDrawer(GravityCompat.START)
    }
    private fun deleteSavedChat(thread: ChatThread){
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete Chat")
            .setMessage("Are you sure you want to delete this chat?")
            .setPositiveButton("Delete") { _, _ ->
                ChatStore.delete(this, thread.id)

                if (currentChatId == thread.id) {
                    session.clear()
                    messages.clear()
                    messageAdapter.setItems(emptyList())
                    currentChatId = null
                }

                loadChatList()
                Toast.makeText(this, "Chat deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onMicClicked() {
        if (!ensureRecordPermissionOrRequest()) return

        if (!whisperLoaded) {
            // First time: load Whisper modules
            gateUi(loaded = true, busy = true)
            toast("Loading Whisper model...")

            lifecycleScope.launch(Dispatchers.IO) {
                val success = loadWhisperModules()
                withContext(Dispatchers.Main) {
                    gateUi(loaded = true, busy = false)
                    if (success) {
                        toast("Whisper loaded. Tap mic to record!")
                        startRecording()
                    } else {
                        toast("Failed to load Whisper modules")
                    }
                }
            }
        } else {
            // Toggle recording
            if (!isRecording) {
                toast("Recording... tap mic to stop")
                startRecording()
            } else {
                toast("Processing audio...")
                stopRecordingAndRunWhisper()
            }
        }
    }

    private suspend fun loadWhisperModules(): Boolean = withContext(Dispatchers.IO) {
        try {
            val modelsDir = AssetMover.getModelsDirectory(this@MainActivity)

            val preprocPath = File(modelsDir, WHISPER_PREPROC_ASSET).absolutePath
            val modelPath = File(modelsDir, WHISPER_MODEL_ASSET).absolutePath
            val tokenizerPath = File(modelsDir, WHISPER_TOKENIZER_ASSET).absolutePath

            // Copy preprocessor from assets if not exists
            if (!File(preprocPath).exists()) {
                withContext(Dispatchers.Main) {
                    toast("Copying Whisper preprocessor from assets...")
                }
                try {
                    AssetMover.copyAssetToModelsDir(this@MainActivity, WHISPER_PREPROC_ASSET)
                    Log.d("ASR", "✓ Preprocessor copied from assets")
                } catch (e: Exception) {
                    Log.e("ASR", "Failed to copy preprocessor: ${e.message}")
                    return@withContext false
                }
            } else {
                Log.d("ASR", "Preprocessor already exists: $preprocPath")
            }

            // Copy model from assets if not exists
            if (!File(modelPath).exists()) {
                withContext(Dispatchers.Main) {
                    toast("Copying Whisper model from assets...")
                }
                try {
                    AssetMover.copyAssetToModelsDir(this@MainActivity, WHISPER_MODEL_ASSET)
                    Log.d("ASR", "✓ Whisper model copied from assets")
                } catch (e: Exception) {
                    Log.e("ASR", "Failed to copy model: ${e.message}")
                    return@withContext false
                }
            } else {
                Log.d("ASR", "Whisper model already exists: $modelPath")
            }
            // Copy tokenizer from assets if not exists
            if (!File(tokenizerPath).exists()) {
                withContext(Dispatchers.Main) {
                    toast("Copying Whisper tokenizer from assets...")
                }
                try {
                    AssetMover.copyAssetToModelsDir(this@MainActivity, WHISPER_TOKENIZER_ASSET)
                    Log.d("ASR", "✓ Whisper tokenizer copied from assets")
                } catch (e: Exception) {
                    Log.e("ASR", "Failed to copy tokenizer: ${e.message}")
                    return@withContext false
                }
            } else {
                Log.d("ASR", "Whisper tokenizer already exists: $modelPath")
            }

            // Verify files exist and have content
            val preprocFile = File(preprocPath)
            val modelFile = File(modelPath)
            val tokenizerFile = File(tokenizerPath)

            Log.d(
                "ASR",
                "Preprocessor: ${preprocFile.absolutePath}, size: ${preprocFile.length()} bytes"
            )
            Log.d("ASR", "Model: ${modelFile.absolutePath}, size: ${modelFile.length()} bytes")
            Log.d(
                "ASR",
                "Tokenizer: ${tokenizerFile.absolutePath}, size: ${tokenizerFile.length()} bytes"
            )

            if (!preprocFile.exists() || preprocFile.length() == 0L) {
                Log.e("ASR", "Preprocessor file missing or empty")
                withContext(Dispatchers.Main) {
                    toast("Whisper preprocessor file not found")
                }
                return@withContext false
            }
            if (!modelFile.exists() || modelFile.length() == 0L) {
                Log.e("ASR", "Model file missing or empty")
                withContext(Dispatchers.Main) {
                    toast("Whisper model file not found")
                }
                return@withContext false
            }
            if (!tokenizerFile.exists() || tokenizerFile.length() == 0L) {
                Log.e("ASR", "Tokenizer file missing or empty")
                withContext(Dispatchers.Main) {
                    toast("Whisper tokenizer file not found")
                }
                return@withContext false
            }

            whisperPreproc = Module.load(preprocPath)
            Log.d(
                "ASR",
                "Preprocessor loaded. Methods: ${whisperPreproc?.methods?.contentToString()}"
            )

            whisperModel = Module.load(modelPath)
            Log.d("ASR", "Model loaded. Methods: ${whisperModel?.methods?.contentToString()}")

            // Load tokenizer from assets (small file, keep in assets)
            //whisperTokenizer = Module.load()

            whisperLoaded = true
            Log.d("ASR", "Whisper modules loaded successfully")
            true
        } catch (t: Throwable) {
            Log.e("ASR", "Failed to load Whisper modules", t)
            whisperLoaded = false
            false
        }
    }

    private fun startRecording() {
        Log.d("ASR", "startRecording() called")
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            toast("No mic permission")
            return
        }
        val bufSize = audioBufferSize
        if (bufSize <= 0) {
            toast("AudioRecord unsupported on this device")
            return
        }

        pcmCollector.reset()

        val ar = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufSize
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            toast("Failed to init audio recorder")
            return
        }

        audioRecord = ar

        isRecording = true
        micBtn.alpha = 0.6f

        ar.startRecording()
        Log.d("ASR", "AudioRecord started, bufferSize=$bufSize")

        // Background thread: read until isRecording == false
        recordingThread = Thread {
            Log.d("ASR", "recordingThread started")
            val buffer = ByteArray(bufSize)

            while (isRecording) {
                val read = ar.read(buffer, 0, buffer.size)
                if (read > 0) {
                    synchronized(pcmCollector) {
                        pcmCollector.write(buffer, 0, read)
                    }
                }
            }
            Log.d("ASR", "recordingThread exiting")
        }.also { it.start() }

        // Safety timeout: auto-stop after 60 seconds
        audioHandler.postDelayed({
            if (isRecording) {
                Log.d("ASR", "Max record time reached (60s), auto-stopping")
                toast("Max record time reached (60s), stopping")
                stopRecordingAndRunWhisper()
            }
        }, 60_000L)
    }

    private fun stopRecordingAndRunWhisper() {
        Log.d("ASR", "stopRecordingAndRunWhisper()")
        if (!isRecording) {
            Log.d("ASR", "Not recording, nothing to stop.")
            return
        }
        isRecording = false

        try {
            audioRecord?.stop()
        } catch (_: Throwable) {
        }
        audioRecord?.release()
        audioRecord = null
        micBtn.alpha = 1f

        val audioBytes = synchronized(pcmCollector) { pcmCollector.toByteArray() }
        if (audioBytes.isEmpty()) {
            toast("No audio captured")
            return
        }

        gateUi(loaded = true, busy = true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val transcription = runWhisperOnce(audioBytes)
                withContext(Dispatchers.Main) {
                    gateUi(loaded = true, busy = false)
                    Log.d("ASR", "Transcription: $transcription")
                }
            } catch (t: Throwable) {
                Log.e("ASR", "Whisper run failed", t)
                withContext(Dispatchers.Main) {
                    gateUi(loaded = true, busy = false)
                    toast("Whisper error: ${t.message}")
                }
            }
        }
    }

    private fun pcm16ToFloatArray(audioBytes: ByteArray): FloatArray {
        val totalSamples = audioBytes.size / 2
        val floatSamples = FloatArray(totalSamples)
        val byteBuffer =
            ByteBuffer.wrap(audioBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until totalSamples) {
            // Normalize 16-bit PCM to [-1 ,1.0]
            val sample = byteBuffer.short.toInt()
            floatSamples[i] = if (sample < 0) {
                sample / 32768.0f
            } else {
                sample / 32767.0f
            }
        }
        return floatSamples
    }

    private fun runWhisperOnce(pcmBytes: ByteArray): String {
        val preprocessor = whisperPreproc ?: error("Processor not loaded")
        val model = whisperModel ?: error("Whisper model not loaded")

        // Convert PCM16 to float
        val floats = pcm16ToFloatArray(pcmBytes)
        Log.d("ASR", "runWhisperOnce: raw samples = ${floats.size}")

        try {
            val targetLength = 480000
            val paddedFloats = FloatArray(targetLength)
            if (floats.size < targetLength) {
                // Pad with zeros
                floats.copyInto(paddedFloats, 0, 0, floats.size)
                // Rest is already zeros
                Log.d("ASR", "Padded from ${floats.size} to $targetLength")
            } else {
                // Truncate
                floats.copyInto(paddedFloats, 0, 0, targetLength)
                Log.d("ASR", "Truncated from ${floats.size} to $targetLength")
            }
            // Use preprocessor
            Log.d("ASR", "Computing mel spectrogram...")
            val audioTensor = Tensor.fromBlob(paddedFloats, longArrayOf(paddedFloats.size.toLong()))
            Log.d("ASR", "AudioTensor: ${audioTensor.shape().contentToString()}")
            Log.d("ASR", "methods:  ${preprocessor.methods.contentToString()}")

            val melTensor = preprocessor.forward(EValue.from(audioTensor))[0].toTensor()
            Log.d("ASR", "Preprocessor done! Mel shape: ${melTensor}")


            // Step 1: Run ENCODER
            Log.d("ASR", "Running encoder...")
            val encoderOut = model.execute("encoder", EValue.from(melTensor))
            val encoderHiddenStates = encoderOut[0].toTensor()
            Log.d("ASR", "Encoder output shape: ${encoderHiddenStates.shape().contentToString()}")

            val tokenIds = decodeGreedy(encoderHiddenStates, maxNewTokens = 96)
            Log.d("ASR", "Token IDs: ${tokenIds.joinToString(",")}")


        } catch (e: Exception) {
            Log.e("ASR", "CRASH DETAILS:")
            Log.e("ASR", "Type: ${e.javaClass.name}")
            Log.e("ASR", "Preprocessor error", e)
            Log.e("ASR", "Error msg: ${e.message}")
            Log.e("ASR", "Stack trace: ${e.stackTraceToString()}")
            throw e
        }
        return ""
    }
    private fun argmaxRow(logits: FloatArray, offset: Int, len: Int): Int {
        var bestIdx = 0
        var bestVal = logits[offset]
        for (i in 1 until len) {
            val v = logits[offset + i]
            if (v > bestVal) { bestVal = v; bestIdx = i }
        }
        return bestIdx
    }

    private fun scalarInt(method: String): Int {
        val ev: EValue = whisperModel!!.execute(method)[0]
        return ev.toInt().toInt()
    }

    private fun decodeGreedy(
        encoderHiddenStates: Tensor,
        maxNewTokens: Int = 96
    ): IntArray {
        val startId = scalarInt("decoder_start_token_id")
        val eosId   = scalarInt("get_eos_id")
        val vocab   = scalarInt("get_vocab_size") // keep if you need it later

        val tokens = IntArray(maxNewTokens + 1)
        var n = 0
        tokens[n++] = startId

        for (step in 0 until maxNewTokens) {
            val tokSlice = tokens.copyOfRange(0, n)
            val tokLong = LongArray(tokSlice.size) { i -> tokSlice[i].toLong() }
            val inTok = Tensor.fromBlob(tokLong, longArrayOf(1L, n.toLong()))

            val out = whisperModel!!.execute("text_decoder", EValue.from(inTok), EValue.from(encoderHiddenStates))

            val logitsT = out[0].toTensor()
            val shape = logitsT.shape()
            val logits = logitsT.dataAsFloatArray

            val nextId: Int = when (shape.size) {
                3 -> {
                    val seq = shape[1].toInt()
                    val v = shape[2].toInt()
                    val rowOffset = (seq - 1) * v
                    argmaxRow(logits, rowOffset, v)
                }
                2 -> {
                    val v = shape[1].toInt()
                    argmaxRow(logits, 0, v)
                }
                1 -> argmaxRow(logits, 0, logits.size)
                else -> throw IllegalStateException("Unexpected logits shape: ${shape.contentToString()}")
            }

            tokens[n++] = nextId
            if (nextId == eosId) break
        }

        return tokens.copyOfRange(0, n)
    }

    private fun gateUi(loaded: Boolean, busy: Boolean, listening: Boolean = false) {
        loadBtn.visibility = View.VISIBLE
        loadBtn.text = if (loaded) "Unload" else "Load"
        loadBtn.isEnabled = !busy
        sendBtn.visibility = View.VISIBLE
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        input.isEnabled = loaded && !busy && !isGenerating
        sendBtn.isEnabled = loaded && (!busy || isGenerating)
        newChatBtn.isEnabled = !busy
        saveBtn.isEnabled = !busy
        micBtn.isEnabled = loaded && !busy && !isGenerating
        micBtn.alpha = if (listening) 0.6f else 1f
    }

    private fun ensureRecordPermissionOrRequest(): Boolean {
        val p = Manifest.permission.RECORD_AUDIO
        val granted = ContextCompat.checkSelfPermission(this, p) ==
                PackageManager.PERMISSION_GRANTED
        return if (granted) {
            true
        } else {
            reqRecordAudio.launch(p)
            false
        }
    }
    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

}


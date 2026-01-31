package com.example.execu_chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
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
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import android.util.Log
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors


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

    // Vosk (inline, no backend)
    private var voskModel: Model? = null
    private var speechService: SpeechService? = null
    private var sttListening = false
    private var sttBuffer = StringBuilder()
    //private var llavaModel: Module? = null

    private var currModelConfig: ModelConfig = ModelConfigs.ALL.first { it.modelType == ModelType.LLAMA_3 }

    // Permission launcher
    private val reqRecordAudio = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            toggleMic()
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
            loadVoskModel()
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
    private fun loadVoskModel(){
        if (!ensureRecordPermissionOrRequest()) return

        if (voskModel == null) {
            // First time: copy directory from assets to filesDir, then load Model
            gateUi(loaded = true, busy = true, listening = false)
            Toast.makeText(this, "Preparing voice model…", Toast.LENGTH_SHORT).show()

            lifecycleScope.launch {
                try {
                    // copy vosk-model-small-en-us/ from assets → filesDir
                    val modelPath = AssetMover.copyAssetDirToFiles(
                        context = this@MainActivity,
                        assetDir = "vosk-model-small-en-us"
                    )
                    // load vosk model from filesDir
                    val model = withContext(Dispatchers.IO) { Model(modelPath) }
                    voskModel = model

                    gateUi(loaded = true, busy = false, listening = false)
                    toggleMic()
                } catch (t: Throwable) {
                    gateUi(loaded = true, busy = false, listening = false)
                    Toast.makeText(
                        this@MainActivity,
                        "Voice model error: ${t.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        } else {
            toggleMic()
        }
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

    private fun toggleMic() {
        val m = voskModel ?: return
        if (!sttListening) {
            try {
                val recognizer = Recognizer(m, 16000.0f)
                speechService = SpeechService(recognizer, 16000.0f).also {
                    it.startListening(voskListener)
                }
                sttBuffer.setLength(0)
                sttListening = true
                input.requestFocus()
                input.setSelection(input.text.length)

                gateUi(loaded = true, busy = false, listening = true)
                Toast.makeText(this, "Listening… tap mic to stop", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Toast.makeText(this, "Mic start failed: ${t.message}", Toast.LENGTH_LONG).show()
                resetMicUi()
            }
        } else {
            stopListening()
            Toast.makeText(this, "Mic off", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopListening() {
        try { speechService?.stop() } catch (_: Throwable) { }
        speechService = null
        sttListening = false

        gateUi(loaded = true, busy = false, listening = false)

        input.postDelayed({
            if (!sttListening) {
                applyBufferToInput()
            }
        }, 150)
    }
    private fun applyBufferToInput() {
        val finalText = sttBuffer.toString().trim()
        input.post {
            Log.d("UI", "setText(final) -> '$finalText'")
            input.setText(finalText)
            input.setSelection(input.text.length )
        }
    }

    private fun extractPartial(json: String?): String {
        return try {
            JSONObject(json ?: "{}").optString("partial", "")
        } catch (_: Throwable) {
            ""
        }
    }

    private fun extractText(json: String?): String {
        return try {
            JSONObject(json ?: "{}").optString("text", "")
        } catch (_: Throwable) {
            ""
        }
    }
    private val voskListener = object : RecognitionListener {

        override fun onPartialResult(hypothesis: String?) {
            Log.d("VOSK", "partial=$hypothesis")
            val partial = extractPartial(hypothesis)
            if (partial.isNotEmpty()) {
                // live preview: sttBuffer + current partial (do not append partial into buffer)
                val preview = buildString {
                    append(sttBuffer.toString())
                    if (isNotEmpty()) append(' ')
                    append(partial)
                }.trim()
                input.post {
                    input.setText(preview)
                    input.setSelection(input.text.length)
                }
            }
        }

        override fun onResult(hypothesis: String?) {
            Log.d("VOSK", "onResult=$hypothesis")
            //
            val text = extractText(hypothesis)
            if (text.isNotEmpty()) {
                if (sttBuffer.isNotEmpty()) sttBuffer.append(' ')
                sttBuffer.append(text)
            }
        }

        override fun onFinalResult(hypothesis: String?) {
            Log.d("VOSK", "finalres=$hypothesis")
            val text = extractText(hypothesis)
            if (text.isNotEmpty()) {
                if (sttBuffer.isNotEmpty()) sttBuffer.append(' ')
                sttBuffer.append(text)
            }
        }

        override fun onError(e: Exception?) {
            Log.e("VOSK", "error", e)
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Voice error: ${e?.message}", Toast.LENGTH_LONG)
                    .show()
                resetMicUi()
            }
        }
        override fun onTimeout() {
            runOnUiThread { stopListening() }
        }
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
    private fun resetMicUi() {
        sttListening = false
        input.hint = ""
        gateUi(loaded = true, busy = false, listening = false)
    }
}


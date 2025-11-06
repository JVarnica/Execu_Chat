package com.example.execu_chat

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import android.util.Log

import org.json.JSONObject
import org.pytorch.executorch.extension.llm.LlmModule


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
    private val messages = mutableListOf<Message>()
    private lateinit var input: EditText
    private lateinit var sendBtn: Button

    private lateinit var micBtn: ImageButton
    private lateinit var chatList: RecyclerView

    @Volatile private var llmModule: LlmModule? = null
    private var firstMessage = false
    private var currentChatId: String? = null

    private lateinit var chatAdapter: ChatAdapter

    private val session = ChatSession()

    // Vosk (inline, no backend)
    private var voskModel: Model? = null
    private var speechService: SpeechService? = null
    private var sttListening = false
    private var sttBuffer = StringBuilder()

    // Permission launcher
    private val reqRecordAudio = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) toggleMic()
        else Toast.makeText(
            this,
            "Microphone permission required for voice input",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_menu)


        initializeViews()
        setupListeners()
        loadChatList()
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
        input = findViewById(R.id.input)

        // Initially disable chat list
        chatList.isEnabled = false
        chatList.alpha = 0.5f
        chatList.layoutManager = LinearLayoutManager(this)
    }
    private fun setupListeners() {
        // Drawer toggle
        menuBtn.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        loadBtn.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            loadModel()
        }
        // New Chat
        newChatBtn.setOnClickListener {
            startNewChat()
        }

        // Save chat
        saveBtn.setOnClickListener {
            saveCurrentChat()
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
            sendMessage()
        }
    }
    private fun loadModel() {
        gateUi(loaded = false, busy = true)

        lifecycleScope.launch(Dispatchers.IO) {
            try {

                val modelPath = AssetMover.copyAssetToFiles(this@MainActivity, "llama1B_4w4d.pte")
                val tokPath = AssetMover.copyAssetToFiles(this@MainActivity, "tokenizer.model")

                val module = LlmModule(
                    LlmModule.MODEL_TYPE_TEXT,
                    modelPath,
                    tokPath,
                    0.8f  // temperature
                )
                val loadResult = module.load()
                if (loadResult == 0) {
                    llmModule = module
                    firstMessage = true //need to give full prompt with <begin_text>, so condition to call this instead of builddelta.

                    withContext(Dispatchers.Main) {
                        toast("Model loaded successfully")
                        gateUi(loaded = true, busy = false)
                        chatList.isEnabled = true
                        chatList.alpha = 1f
                    }
                } else {
                    throw Exception("Model load failed with code: $loadResult")
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
    private fun startNewChat() {
        val module = llmModule
        if (module == null) {
            toast("Please load model first!!!")
            return
        }
        Log.d("MainActivity", "--New chat started!!--")
        session.clear()
        messages.clear()
        messageAdapter.setItems(messages)
        currentChatId = null
        drawerLayout.closeDrawer(GravityCompat.START)

        gateUi(loaded = false, busy = true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                llmModule?.resetContext()
                firstMessage = true
                withContext(Dispatchers.Main) {
                    gateUi(loaded = true, busy = false)
                    toast("New converstation started!!")
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
    private fun sendMessage(){
        val msg = input.text.toString().trim()
        if (msg.isEmpty() || !sendBtn.isEnabled)return

        val module = llmModule
        if (module == null) {
            toast("Please load model first!!!")
            return
        }
        input.text.clear()
        // update session + user bubble placeholder for assis response
        session.appendUser(msg)
        messages.add(Message(msg, isUser = true))
        messageAdapter.addItem(Message(msg, isUser = true))
        messagesRecyclerView.scrollToPosition(messages.size - 1)

        val assistantMsgIndex = messages.size
        messages.add(Message("", isUser = false))
        messageAdapter.addItem(Message(msg, isUser = false))


        val responseBuilder = StringBuilder()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val prompt = if (firstMessage) {
                    firstMessage = false
                    session.fullPrompt()
                } else {
                    ChatFormatter.buildDeltaFromUser(msg)
                }
                Log.d("PROMPT", "Sending prompt:\n$prompt")
                Log.d("PROMPT", "Full session:\n${session.fullPrompt()}")

                val cb = object : LlmCallback {
                    override fun onResult(s: String) {
                        val clean = ChatFormatter.sanitizeChunk(s)
                        if (clean.isEmpty()) return
                        responseBuilder.append(clean)
                        val partial = responseBuilder.toString()
                        runOnUiThread {
                            messages[assistantMsgIndex] = Message(partial, isUser = false)
                            messageAdapter.updateItem(assistantMsgIndex, Message(partial, isUser = false))
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
                module.generate(prompt, 192, cb, false)

                val finalText = responseBuilder.toString().trim()
                Log.d("GENERATE", "Generate returned: '$finalText'")  // ✅ Debug log

                withContext(Dispatchers.Main) {
                    if (finalText.isNotEmpty()) {
                        session.appendAssistant(finalText)
                        Log.d("REPLY", "Final text: '$finalText'")
                        messages[assistantMsgIndex] = Message(finalText, isUser = false)
                        messageAdapter.updateItem(assistantMsgIndex, Message(finalText, isUser = false))
                        messagesRecyclerView.scrollToPosition(assistantMsgIndex)
                    } else {
                        messages.removeAt(assistantMsgIndex)
                        messageAdapter.removeAt(assistantMsgIndex)
                        toast("No response generated")
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Generation failed", e)
                withContext(Dispatchers.Main) {
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
    private fun loadSavedChat(thread: ChatThread) {
        Log.d("MainActivity", "--Loading saved chat!!--")
        val module = llmModule
        if (module == null) {
            val msg = "Please load model first!!"
            toast(msg)
            drawerLayout.closeDrawer(GravityCompat.START)
            return
        }
        llmModule?.resetContext()
        firstMessage = true
        val content = ChatStore.load(thread)
        session.resetFromTranscript(content)
        currentChatId = thread.id
        // ✅ Convert transcript to message bubbles/UI
        messages.clear()
        for (turn in session.turns) {
            messages.add(Message(
                text = turn.text,
                isUser = turn.role == Turn.Role.User
            ))
        }
        messageAdapter.setItems(messages)
        messagesRecyclerView.scrollToPosition(maxOf(0, messages.size - 1))

        toast("Chat loaded")
        drawerLayout.closeDrawer(GravityCompat.START)

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
            loadBtn.visibility = if (loaded) View.GONE else View.VISIBLE
            sendBtn.visibility = if (loaded) View.VISIBLE else View.GONE
            progress.visibility = if (busy) View.VISIBLE else View.GONE
            input.isEnabled = loaded && !busy
            sendBtn.isEnabled = loaded && !busy
            newChatBtn.isEnabled = !busy
            saveBtn.isEnabled = !busy
            micBtn.isEnabled = loaded && !busy
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


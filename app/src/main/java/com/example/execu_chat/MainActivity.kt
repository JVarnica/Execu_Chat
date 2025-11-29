package com.example.execu_chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmModule
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    @Volatile
    private var llmModule: LlmModule? = null
    private var firstMessage = false
    private var currentChatId: String? = null

    private lateinit var chatAdapter: ChatAdapter
    private val session = ChatSession()

    // Whisper modules
    private var whisperPreproc: Module? = null
    private var whisperModel: Module? = null
    private var whisperIdToToken: Map<Int, String>? = null
    private var whisperLoaded = false

    // decoder start token (set this to whatever you used in export)
    private val decoderStartId = 50258

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

    // Permission launcher
    private val reqRecordAudio = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onMicClicked()
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

        chatList.isEnabled = false
        chatList.alpha = 0.5f
        chatList.layoutManager = LinearLayoutManager(this)
    }

    private fun setupListeners() {
        menuBtn.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        loadBtn.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            loadModel()
        }

        newChatBtn.setOnClickListener {
            startNewChat()
        }

        saveBtn.setOnClickListener {
            saveCurrentChat()
        }

        chatAdapter = ChatAdapter(
            { thread -> loadSavedChat(thread) },
            onDelete = { thread -> deleteSavedChat(thread) }
        )
        chatList.adapter = chatAdapter

        micBtn.setOnClickListener {
            onMicClicked()
        }

        sendBtn.setOnClickListener {
            sendMessage()
        }
    }

    // ---------------------- LLM LOADING / CHAT ------------------------

    private fun loadModel() {
        gateUi(loaded = false, busy = true)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val modelPath =
                    AssetMover.copyAssetToFiles(this@MainActivity, "llama1B_4w4d.pte")
                val tokPath =
                    AssetMover.copyAssetToFiles(this@MainActivity, "tokenizer.model")

                val module = LlmModule(
                    LlmModule.MODEL_TYPE_TEXT,
                    modelPath,
                    tokPath,
                    0.8f
                )
                val loadResult = module.load()
                if (loadResult == 0) {
                    llmModule = module
                    firstMessage = true

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

    private fun sendMessage() {
        val msg = input.text.toString().trim()
        if (msg.isEmpty() || !sendBtn.isEnabled) return

        val module = llmModule
        if (module == null) {
            toast("Please load model first!!!")
            return
        }
        input.text.clear()

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
                            messages[assistantMsgIndex] =
                                Message(partial, isUser = false)
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
                            val numGeneratedTokens = j.optLong("generated_tokens")
                            val inferenceEndMs = j.optLong("inference_end_ms")
                            val promptEvalEndMs = j.optLong("prompt_eval_end_ms")
                            val decodeTime =
                                (inferenceEndMs - promptEvalEndMs).toDouble()
                            val tps =
                                (numGeneratedTokens.toDouble() / decodeTime) * 1000

                            val line = buildString {
                                append("$numGeneratedTokens tok . ")
                                append(String.format("%.2f tok/s", tps))
                                append(" . decode ${decodeTime}ms")
                            }
                            runOnUiThread {
                                statsText.text = line
                                statsText.visibility = View.VISIBLE
                            }
                            Log.d("LLM-Stats", line)
                        } catch (t: Throwable) {
                            Log.w(
                                "LLM-STATS",
                                "Failed to parse stats: ${t.message}"
                            )
                        }
                    }
                }
                Log.d("SEND", "Calling generate with continuous prompt")
                module.generate(prompt, 192, cb, false)

                val finalText = responseBuilder.toString().trim()
                Log.d("GENERATE", "Generate returned: '$finalText'")

                withContext(Dispatchers.Main) {
                    if (finalText.isNotEmpty()) {
                        session.appendAssistant(finalText)
                        Log.d("REPLY", "Final text: '$finalText'")
                        messages[assistantMsgIndex] =
                            Message(finalText, isUser = false)
                        messageAdapter.updateItem(
                            assistantMsgIndex,
                            Message(finalText, isUser = false)
                        )
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
    }

    private fun loadChatList() {
        val threads = ChatStore.list(this)
        chatAdapter.submitList(threads)
    }

    private fun saveCurrentChat() {
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

    private fun deleteSavedChat(thread: ChatThread) {
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

    // ---------------------- WHISPER AUDIO / RUN-ONCE ------------------------

    private fun onMicClicked() {
        if (!ensureRecordPermissionOrRequest()) return

        if (!whisperLoaded) {
            gateUi(loaded = true, busy = true)
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = loadWhisperModules()
                withContext(Dispatchers.Main) {
                    gateUi(loaded = true, busy = false)
                    if (ok) {
                        toast("Whisper loaded. Recording 5s…")
                        startRecording()
                    } else {
                        toast("Failed to load Whisper modules")
                    }
                }
            }
        } else {
            if (!isRecording) {
                toast("Recording 5s…")
                startRecording()
            } else {
                toast("Stopping recording…")
                stopRecordingAndRunWhisper()
            }
        }
    }

    private suspend fun loadWhisperModules(): Boolean {
        return try {
            val modelPath = AssetMover.copyAssetToFiles(
                this@MainActivity,
                "whisper-small-fp32.pte"
            )
            whisperModel = Module.load(modelPath)
            if (whisperIdToToken == null) {
                val vocabPath = AssetMover.copyAssetToFiles(
                    this@MainActivity,
                    "vocab.json"  // or "whisper/vocab.json" depending on your assets tree
                )
                whisperIdToToken = loadWhisperVocab(vocabPath)
            }
            whisperLoaded = true

            Log.d("ASR", "Whisper modules loaded")
            true
        } catch (t: Throwable) {
            Log.e("ASR", "Failed to load Whisper modules", t)
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
                    toast("Whisper transcription: $transcription")
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
            val sample = byteBuffer.short.toInt()
            floatSamples[i] = if (sample < 0) {
                sample / 32768.0f
            } else {
                sample / 32767.0f
            }
        }
        return floatSamples
    }
    private suspend fun loadWhisperVocab(vocabPath: String): Map<Int, String> {

        val jsonText = File(vocabPath).readText(Charsets.UTF_8)

        val root = JSONObject(jsonText)

        val map = HashMap<Int, String>(root.length())
        val keys = root.keys()
        while (keys.hasNext()) {
            val token = keys.next()
            val id = root.getInt(token)
            map[id] = token   // id -> token (for decoding)
        }
        return map
    }

    private fun runWhisperOnce(pcmBytes: ByteArray): String {
        // Only need the Whisper model, not the preprocessor anymore!
        val model = whisperModel ?: error("Whisper model not loaded")

        // Convert PCM16 to float
        val floats = pcm16ToFloatArray(pcmBytes)
        Log.d("ASR", "runWhisperOnce: raw samples = ${floats.size}")

        // Compute mel spectrogram in pure Kotlin (no .pte needed!)
        Log.d("ASR", "Computing mel spectrogram...")
        val melData = WhisperMelSpectrogram.compute(floats)
        val melShape = WhisperMelSpectrogram.getOutputShape()  // [1, 80, 3000]
        Log.d("ASR", "Mel spectrogram computed: ${melData.size} values, shape=${melShape.contentToString()}")

        // Create mel tensor [1, 80, 3000]
        val melTensor = Tensor.fromBlob(melData, melShape)
        // val melTensor = Tensor.fromBlob(melData, longArrayOf(1L, 80L, 3000L))
        Log.d("ASR", "melTensor created")

        // Step 1: Run ENCODER
        Log.d("ASR", "Running encoder...")
        val encoderOut = model.execute("encoder", EValue.from(melTensor))
        val encoderHiddenStates = encoderOut[0].toTensor()
        Log.d("ASR", "Encoder output shape: ${encoderHiddenStates.shape().contentToString()}")

        // Whisper special tokens
        val startOfTranscript = 50258L
        val endOfTranscript = 50257L
        val english = 50259L
        val transcribe = 50359L
        val noTimestamps = 50363L

        // Build initial prompt: <|startoftranscript|><|en|><|transcribe|><|notimestamps|>
        val generatedTokens = mutableListOf(startOfTranscript, english, transcribe, noTimestamps)

        val maxTokens = 224  // Whisper's max generation length
        var cachePos = 0L

        Log.d("ASR", "Starting decoding loop...")

        // Autoregressive decoding loop
        while (generatedTokens.size < maxTokens) {
            // Create input tensor with the last token
            val inputToken = generatedTokens.last()
            val decoderInputIds = Tensor.fromBlob(
                longArrayOf(inputToken),
                longArrayOf(1L, 1L)
            )

            val cachePosition = Tensor.fromBlob(
                longArrayOf(cachePos),
                longArrayOf(1L)
            )

            // Run decoder
            val decoderOut = model.execute(
                "text_decoder",
                EValue.from(decoderInputIds),
                EValue.from(encoderHiddenStates),
                EValue.from(cachePosition)
            )

            val logits = decoderOut[0].toTensor().dataAsFloatArray

            // Argmax to get next token
            var nextToken = 0
            var maxLogit = logits[0]
            for (i in 1 until logits.size) {
                if (logits[i] > maxLogit) {
                    maxLogit = logits[i]
                    nextToken = i
                }
            }

            Log.d("ASR", "Position $cachePos: token $nextToken")

            // Check for end of transcript
            if (nextToken.toLong() == endOfTranscript) {
                Log.d("ASR", "End of transcript reached")
                break
            }

            generatedTokens.add(nextToken.toLong())
            cachePos++
        }

        Log.d("ASR", "Generated ${generatedTokens.size} tokens")

        // Decode tokens to text (skip the prompt tokens)
        val textTokens = generatedTokens.drop(4)  // Skip <|startoftranscript|><|en|><|transcribe|><|notimestamps|>
        val transcription = decodeTokens(textTokens)

        Log.d("ASR", "Transcription: $transcription")

        return transcription
    }
    private fun decodeTokens(tokens: List<Long>): String {
        // Filter out special tokens (>= 50257) and decode
        val textTokens = tokens.filter { it < 50257 }

        val vocab = whisperIdToToken ?: return textTokens.joinToString(" ") { "[$it]" }

        val pieces = textTokens.mapNotNull { id ->
            vocab[id.toInt()]
        }

        return pieces.joinToString("")
            .replace("Ġ", " ")   // space marker
            .replace("Ċ", "\n")  // newline marker
            .trim()
    }
    // ---------------------- UI HELPERS ------------------------

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
        runOnUiThread {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }
}

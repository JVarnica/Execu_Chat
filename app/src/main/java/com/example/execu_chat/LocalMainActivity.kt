package com.example.execu_chat

import android.Manifest
import android.app.ActivityManager
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
import kotlinx.coroutines.launch
import androidx.core.view.GravityCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import androidx.activity.viewModels

class LocalChatActivity : AppCompatActivity() {


    private val viewModel: MainViewModel by viewModels()
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var loadBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var menuBtn: ImageButton
    private lateinit var memUsage: TextView
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
    private lateinit var chatAdapter: ChatAdapter

    private var selectedImageUri: Uri? = null

    //memory update
    private var memoryUpdateHandler: Handler? = null
    private val memoryUpdateRunnable = object : Runnable {
        override fun run() {
            updateMemoryUsage()
            memoryUpdateHandler?.postDelayed(this, 1000)
        }
    }
    // Audio recording for Whisper
    private var audioRecord: AudioRecord? = null

    private var recordingThread: Thread? = null
    private var isRecording = false
    private val audioHandler = Handler(Looper.getMainLooper())
    private val pcmCollector = ByteArrayOutputStream()

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val audioBufferSize by lazy {
        AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    }
    // Permission launcher
    private val reqRecordAudio = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onMicClicked()
        } else toast(
            "Microphone permission required for voice input"
        )
    }
    private val reqPickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            Log.d("Main Activity", "Image Selected: $it")
            viewModel.prefillImage(it, contentResolver)

        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_menu)

        initializeViews()
        setupListeners()
        observeViewModel()
        loadChatList()
        startMemoryMonitoring()
    }
    override fun onResume() {
        super.onResume()
        startMemoryMonitoring()
    }

    override fun onPause() {
        super.onPause()
        stopMemoryMonitoring()
    }

    override fun onStop() {
        super.onStop()
        stopMemoryMonitoring()
        if (viewModel.uiState.value.isGenerating) {
            viewModel.stopGen()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMemoryMonitoring()
        stopRecording()
    }

    private fun initializeViews() {

        // Drawer + UI refs
        drawerLayout = findViewById(R.id.drawerLayout)
        loadBtn = findViewById(R.id.loadBtn)
        progress = findViewById(R.id.progress)

        menuBtn = findViewById(R.id.menuBtn)
        memUsage = findViewById(R.id.memUsage)
        newChatBtn = findViewById(R.id.newChatBtn)
        saveBtn = findViewById(R.id.saveBtn)
        messagesRecyclerView = findViewById(R.id.messagesRecyclerView)
        statsText = findViewById(R.id.statsText)
        sendBtn = findViewById(R.id.sendBtn)
        chatList = findViewById(R.id.chatList)
        micBtn = findViewById(R.id.micBtn)
        attachBtn = findViewById(R.id.galleryBtn)
        input = findViewById(R.id.input)
        backendSpinner = findViewById(R.id.backendSpinner)
        modelSpinner = findViewById(R.id.modelSpinner)
        sysPrompt = findViewById(R.id.sysBtn)

        //setup recyclerview
        messageAdapter = MessageAdapter()
        messagesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@LocalChatActivity)
            adapter = messageAdapter
        }

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
            val state = viewModel.uiState.value
            if (!state.isModelLoaded) {
                viewModel.loadModel()
            } else {
                viewModel.unloadModel()
            }
        }
        // New Chat
        newChatBtn.setOnClickListener {
            viewModel.startNewChat()
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        // Save chat
        saveBtn.setOnClickListener {
            viewModel.saveCurrentChat()
            loadChatList()
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        // System prompt - updates when user presses "Done" on keyboard
        // Also update when user taps away (loses focus)
        sysPrompt.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                viewModel.updateSystemPrompt(sysPrompt.text.toString())
            }
        }

        // RecyclerView for saved chats -open saved chat
        chatAdapter = ChatAdapter(
            { thread -> viewModel.loadSavedChat(thread) },
            onDelete = { thread -> viewModel.deleteSavedChat(thread) }
        )
        chatList.adapter = chatAdapter

        micBtn.setOnClickListener {
            onMicClicked()
        }
        // Send button
        sendBtn.setOnClickListener {
            val state = viewModel.uiState.value
            if (state.isGenerating) {
                viewModel.stopGen()
            } else {
                sendMessage()
            }
        }

        attachBtn.setOnClickListener {
            val config = viewModel.uiState.value.selectedModelConfig
            if (config.modelType.isMultiModal()) {
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
                val backend = when (position) {
                    0 -> BackendType.XNNPACK
                    1 -> BackendType.VULKAN
                    else -> BackendType.XNNPACK
                }
                viewModel.updateBackend(backend)
                Log.d(
                    "MainActivity",
                    "Backend selected: ${backend}"
                )
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
                viewModel.updateModelConfig(ModelConfigs.ALL[position])
                Log.d("MainActivity", "Model selected: ${viewModel.uiState.value.selectedModelConfig.displayName}")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Keep current selection
                Log.d("MainActivity", "Spinner selection cleared, keeping: ${viewModel.uiState.value.selectedModelConfig.displayName}")
            }
        }
    }
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUI(state)
                }
            }
        }
    }
    private fun updateUI(state: ChatUiState) {
        // Update messages
        messageAdapter.setItems(state.messages)
        if (state.messages.isNotEmpty()) {
            messagesRecyclerView.scrollToPosition(state.messages.size - 1)
        }

        // Update load button
        loadBtn.text = if (state.isModelLoaded) "Unload" else "Load"
        loadBtn.isEnabled = !state.isLoading

        // Update progress
        progress.visibility = if (state.isLoading) View.VISIBLE else View.GONE

        // Update input controls
        input.isEnabled = state.isModelLoaded && !state.isLoading && !state.isGenerating && !state.isTranscribing
        sendBtn.isEnabled = state.isModelLoaded && (!state.isLoading || state.isGenerating)
        micBtn.isEnabled = state.isModelLoaded && !state.isLoading && !state.isGenerating
        micBtn.alpha = if (isRecording) 0.6f else 1f

        // Update send button icon
        if (state.isGenerating) {
            sendBtn.setImageResource(R.drawable.outline_block_24)
            sendBtn.contentDescription = "Stop"
        } else {
            sendBtn.setImageResource(R.drawable.baseline_send_24)
            sendBtn.contentDescription = getString(R.string.send_button)
        }

        // Update other buttons
        newChatBtn.isEnabled = !state.isLoading
        saveBtn.isEnabled = !state.isLoading

        // Enable chat list when model is loaded
        chatList.isEnabled = state.isModelLoaded
        chatList.alpha = if (state.isModelLoaded) 1f else 0.5f

        // Update stats
        if (state.showStats) {
            statsText.text = state.statsText
            statsText.visibility = View.VISIBLE
        }

        // Handle toast messages
        state.toastMessage?.let { message ->
            toast(message)
            viewModel.clearToast()
        }

        // Handle transcribed text from Whisper
        state.transcribedText?.let { text ->
            if (text.isNotBlank()) {
                input.setText(text)
                input.setSelection(input.text.length)
            }
            viewModel.clearTranscribedText()
        }
    }
    private fun sendMessage() {
        val msg = input.text.toString().trim()
        if (msg.isEmpty()) return

        input.text.clear()
        val hasImage = selectedImageUri != null
        viewModel.sendMessage(msg, hasImage)
        selectedImageUri = null
    }
    private fun loadChatList() {
        val threads = ChatStore.list(this)
        chatAdapter.submitList(threads)
    }
    private fun onMicClicked() {
        if (!ensureRecordPermissionOrRequest()) return

        if (!viewModel.isWhisperLoaded()) {
            // Load Whisper first, then start recording
            viewModel.loadWhisperIfNeeded {
                startRecording()
            }
        } else {
            // Toggle recording
            if (!isRecording) {
                startRecording()
            } else {
                stopRecordingAndTranscribe()
            }
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
                stopRecordingAndTranscribe()
            }
        }, 60_000L)
    }
    private fun stopRecording() {
        if (!isRecording) return

        isRecording = false
        try { audioRecord?.stop() } catch (_: Throwable) {}
        audioRecord?.release()
        audioRecord = null
        micBtn.alpha = 1f
    }

    private fun stopRecordingAndTranscribe() {
        Log.d("ASR", "stopRecordingAndTranscribe()")
        if (!isRecording) {
            Log.d("ASR", "Not recording, nothing to stop.")
            return
        }

        stopRecording()

        val audioBytes = synchronized(pcmCollector) { pcmCollector.toByteArray() }
        if (audioBytes.isEmpty()) {
            toast("No audio captured")
            return
        }

        toast("Processing audio...")

        // Write WAV file and send to ViewModel for transcription
        val wavFile = File(cacheDir, "whisper_recording.wav")
        writeWavPcm16Mono(wavFile, audioBytes, sampleRate)

        viewModel.transcribeAudio(wavFile) { transcription ->
            // Transcription result handled via uiState.transcribedText
            Log.d("ASR", "Transcription complete: $transcription")
        }
    }
    private fun writeWavPcm16Mono(outFile: File, pcm16le: ByteArray, sampleRate: Int) {
        val numChannels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * numChannels * (bitsPerSample / 8)
        val blockAlign = (numChannels * (bitsPerSample / 8)).toShort()

        val dataSize = pcm16le.size
        val riffChunkSize = 36 + dataSize

        FileOutputStream(outFile).use { fos ->
            fos.write(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()))
            fos.write(intToLE(riffChunkSize))
            fos.write(byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()))

            fos.write(byteArrayOf('f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte()))
            fos.write(intToLE(16))
            fos.write(shortToLE(1)) // PCM
            fos.write(shortToLE(numChannels.toShort()))
            fos.write(intToLE(sampleRate))
            fos.write(intToLE(byteRate))
            fos.write(shortToLE(blockAlign))
            fos.write(shortToLE(bitsPerSample.toShort()))

            fos.write(byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte()))
            fos.write(intToLE(dataSize))
            fos.write(pcm16le)
        }
    }
    private fun intToLE(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte()
    )
    private fun shortToLE(v: Short) = byteArrayOf(
        (v.toInt() and 0xFF).toByte(),
        ((v.toInt() ushr 8) and 0xFF).toByte()
    )
    private fun updateMemoryUsage() {
        val memoryInfo = ActivityManager.MemoryInfo()
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return

        activityManager.getMemoryInfo(memoryInfo)
        val totalMem = memoryInfo.totalMem / (1024 * 1024)
        val availableMem = memoryInfo.availMem / (1024 * 1024)
        val usedMem = totalMem - availableMem

        runOnUiThread {
            memUsage.text = "${usedMem}MB"
        }
    }
    private fun startMemoryMonitoring() {
        stopMemoryMonitoring() // Stop any existing monitoring
        memoryUpdateHandler = Handler(Looper.getMainLooper())
        memoryUpdateHandler?.post(memoryUpdateRunnable)
    }
    private fun stopMemoryMonitoring() {
        memoryUpdateHandler?.removeCallbacks(memoryUpdateRunnable)
        memoryUpdateHandler = null
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
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
package com.example.execu_chat
//Cloud mainactivity all UI logic. Calls viewModel.

import android.graphics.PorterDuff
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class ToolMode(val label: String, val icon: String) {
    NONE("Chat", "💬"),
    SEARCH("Search", "🔍"),
    DEEP_RESEARCH("Deep Research", "🔬"),
    // Future tools go here:
    // SUMMARISE("Summarise", "📝"),
    // TRANSLATE("Translate", "🌐"),
}

class CloudChatActivity : AppCompatActivity() {
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var input: EditText
    private lateinit var send: ImageButton
    private lateinit var adapter: MessageAdapter
    private lateinit var menu: ImageButton
    private lateinit var newChatBtn: Button
    private lateinit var save: Button
    private lateinit var chatList: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var plusBtn: ImageButton
    private lateinit var toolChip: TextView
    private lateinit var toolChipContainer: LinearLayout
    private lateinit var viewModel: CloudChatViewModel


    // Research progress panel views
    private lateinit var researchPanel: LinearLayout
    private lateinit var researchTitle: TextView
    private lateinit var researchCancel: TextView
    private lateinit var researchProgressBar: ProgressBar
    private lateinit var researchStatus: TextView
    private lateinit var researchSources: TextView
    private lateinit var researchSummaries: TextView

    private var activeTool: ToolMode = ToolMode.NONE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.cloud_menu)

        // Initialize ViewModel
        viewModel = ViewModelProvider(this)[CloudChatViewModel::class.java]
        // initialize buttons
        drawerLayout = findViewById(R.id.drawer_layout2)
        recyclerView = findViewById(R.id.messagesRecyclerView)
        input = findViewById(R.id.input)
        send = findViewById(R.id.sendBtn)
        menu = findViewById(R.id.menuBtn)
        newChatBtn = findViewById(R.id.newChatBtn)
        save = findViewById(R.id.saveBtn)
        chatList = findViewById(R.id.chatList)
        plusBtn = findViewById(R.id.plusBtn)
        toolChip = findViewById(R.id.toolChip)
        toolChipContainer = findViewById(R.id.toolChipContainer)

        // Research panel views
        researchPanel = findViewById(R.id.researchPanel)
        researchTitle = findViewById(R.id.researchTitle)
        researchCancel = findViewById(R.id.researchCancel)
        researchProgressBar = findViewById(R.id.researchProgressBar)
        researchStatus = findViewById(R.id.researchStatus)
        researchSources = findViewById(R.id.researchSources)
        researchSummaries = findViewById(R.id.researchSummaries)

        adapter = MessageAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        recyclerView.adapter = adapter

        // Setup chat list adapter
        chatAdapter = ChatAdapter(
            onClick = { thread -> loadSavedChat(thread) },
            onDelete = { thread -> deleteSavedChat(thread) }
        )
        chatList.layoutManager = LinearLayoutManager(this)
        chatList.adapter = chatAdapter

        // Load saved chats
        refreshChatList()

        // Observe messages
        lifecycleScope.launch {
            viewModel.messages.collectLatest { messages ->
                adapter.setItems(messages)
                if (messages.isNotEmpty()) {
                    recyclerView.scrollToPosition(messages.size - 1)
                }
            }
        }
        // Observe loading state
        lifecycleScope.launch {
            viewModel.isLoading.collectLatest { isLoading ->
                send.isEnabled = !isLoading
            }
        }
        lifecycleScope.launch {
            viewModel.isResearching.collectLatest { researching ->
                send.isEnabled = !researching && !viewModel.isLoading.value
                if (researching) {
                    researchPanel.visibility = View.VISIBLE
                    researchProgressBar.progress = 0
                    researchStatus.text = "Starting…"
                    researchSources.text = "📚 0 sources"
                    researchSummaries.text = "📝 0 summaries"
                } else {
                    // Delay hiding so user sees "done" state briefly
                    researchPanel.postDelayed({
                        if (!viewModel.isResearching.value) {
                            researchPanel.visibility = View.GONE
                        }
                    }, 2000)
                }
            }
        }
        lifecycleScope.launch {
            viewModel.researchProgress.collectLatest { progress ->
                progress?.let { p ->
                    // Update progress bar
                    researchProgressBar.progress = (p.progress * 100).toInt()

                    // Update phase icon + status message
                    val phaseIcon = when (p.phase) {
                        "planning" -> "📋"
                        "searching" -> "🔍"
                        "reading" -> "📖"
                        "synthesising" -> "✍️"
                        "done" -> "✅"
                        "error" -> "❌"
                        else -> "⏳"
                    }
                    researchStatus.text = "$phaseIcon ${p.message}"

                    // Update counters
                    researchSources.text = "📚 ${p.sourcesFound.size} sources"
                    researchSummaries.text = "📝 ${p.summaries.size} summaries"

                    // Update title with percentage
                    val pct = (p.progress * 100).toInt()
                    researchTitle.text = if (p.phase == "done") {
                        "🔬 Research Complete"
                    } else {
                        "🔬 Deep Research ($pct%)"
                    }
                    // Update the last assistant message with progress info
                    // or show a dedicated progress view
                }
            }
        }
        // Observe errors
        lifecycleScope.launch {
            viewModel.error.collectLatest { error ->
                error?.let {
                    Toast.makeText(this@CloudChatActivity, it, Toast.LENGTH_SHORT).show()
                }
            }
        }
        menu.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }
        // Save button
        save.setOnClickListener {
            saveCurrentChat()
        }

        // New chat button
        newChatBtn.setOnClickListener {
            startNewChat()
        }
        // ── Research cancel button ───────────────────────────────────
        researchCancel.setOnClickListener {
            viewModel.cancelDeepResearch()
            researchPanel.visibility = View.GONE
        }
        //pop up tools menu
        plusBtn.setOnClickListener { anchor ->
            showToolMenu(anchor)
        }

        // ── Tool chip dismiss (tap X to go back to plain chat) ───────
        toolChipContainer.setOnClickListener {
            setActiveTool(ToolMode.NONE)
        }
        send.setOnClickListener {
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) {
                input.setText("")
                when (activeTool) {
                    ToolMode.NONE -> {
                        viewModel.sendMessage(text, enableSearch = false)
                        Log.d("MainAc", "send message NORMAL")
                    }
                    ToolMode.SEARCH -> {
                        viewModel.sendMessage(text, enableSearch = true)
                        Log.d("MainAc", "send message SEARCH")
                    }
                    ToolMode.DEEP_RESEARCH -> {
                        viewModel.startDeepResearch(text)
                        Log.d("MainAc", "Depp research activated ")
                    }
                }
            }
        }
    }
    private fun showToolMenu(anchor: View) {
        val popup = PopupMenu(this, anchor, Gravity.TOP or Gravity.START)

        // Build menu items from ToolMode enum (skip NONE)
        ToolMode.entries
            .filter { it != ToolMode.NONE }
            .forEachIndexed { index, tool ->
                popup.menu.add(0, index, index, "${tool.icon}  ${tool.label}")
            }

        popup.setOnMenuItemClickListener { item ->
            val tools = ToolMode.entries.filter { it != ToolMode.NONE }
            val selected = tools.getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
            setActiveTool(selected)
            true
        }

        popup.show()
    }

    // ── Activate / deactivate a tool ─────────────────────────────────

    private fun setActiveTool(tool: ToolMode) {
        activeTool = tool

        if (tool == ToolMode.NONE) {
            toolChipContainer.visibility = View.GONE
            input.hint = "Message..."
        } else {
            toolChipContainer.visibility = View.VISIBLE
            toolChip.text = "${tool.icon} ${tool.label}  ✕"
            input.hint = "${tool.label}..."
        }
    }
    private fun saveCurrentChat() {
        viewModel.saveCurrentChat(this)
        Toast.makeText(this, "Chat saved", Toast.LENGTH_SHORT).show()
        refreshChatList()
    }

    private fun loadSavedChat(thread: ChatThread) {
        viewModel.loadChat(this, thread)
        Toast.makeText(this, "Chat loaded", Toast.LENGTH_SHORT).show()
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun deleteSavedChat(thread: ChatThread) {
        viewModel.deleteChat(this, thread)
        refreshChatList()
    }

    private fun startNewChat() {
        viewModel.clearMessages()
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun refreshChatList() {
        val threads = viewModel.getSavedChats(this)
        chatAdapter.submitList(threads)
    }
}


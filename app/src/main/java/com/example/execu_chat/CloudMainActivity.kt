package com.example.execu_chat
//Cloud mainactivity all UI logic. Calls viewModel.

import android.content.Intent
import android.graphics.PorterDuff
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AlertDialog
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
    RAG("Memory", "🧠"),
    DEEP_RESEARCH("Deep Research", "🔬"),
}

class CloudChatActivity : AppCompatActivity() {
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var input: EditText
    private lateinit var send: ImageButton
    private lateinit var adapter: MessageAdapter
    private lateinit var menu: ImageButton
    private lateinit var newChatBtn: Button
    private lateinit var logOutBtn: Button
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
    //non null when search query
    private var agentSearchQuery: String? = null

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
        logOutBtn = findViewById(R.id.logOutBtn)
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
        viewModel.refreshChatList()

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

        lifecycleScope.launch {
            viewModel.agentSearchQuery.collectLatest { query ->
                agentSearchQuery = query
                renderToolChip()
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
        // Observe server chat list
        lifecycleScope.launch {
            viewModel.serverChats.collectLatest { items ->
                val threads = items.map { item ->
                    ChatThread(
                        id = item.convoId,
                        title = item.title.ifBlank { "(untitled)" },
                        preview = formatTimestamp(item.updatedAt),
                        path = "" // used for server chats
                    )
                }
                chatAdapter.submitList(threads)
            }
        }
        menu.setOnClickListener {
            viewModel.refreshChatList()
            drawerLayout.openDrawer(GravityCompat.START)
        }
        // Save button
        save.setOnClickListener {
            showSaveDialog()
        }

        // New chat button
        newChatBtn.setOnClickListener {
            startNewChat()
        }
        // Logout back to loginActivity
        logOutBtn.setOnClickListener {
            TokenManager.clear(this)
            startActivity(Intent(this, LoginActivity::class.java))
            finishAffinity()
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
            if (agentSearchQuery == null) {
                setActiveTool(ToolMode.NONE)
            }
        }
        send.setOnClickListener {
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) {
                input.setText("")
                when (activeTool) {
                    ToolMode.NONE -> {
                        viewModel.sendMessage(text)
                        Log.d("MainAc", "send message NORMAL")
                    }

                    ToolMode.DEEP_RESEARCH -> {
                        viewModel.startDeepResearch(text)
                        Log.d("MainAc", "Depp research activated ")
                    }

                    ToolMode.RAG -> {
                        viewModel.sendMessage(text, enableRag = true)
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

        if (tool == ToolMode.NONE) input.hint = "Message..."
        else input.hint = "${tool.label}"
        renderToolChip()
        }
    private fun renderToolChip() {
        val searching = agentSearchQuery
        when {
            searching != null -> {
                toolChipContainer.visibility = View.VISIBLE
                val display = if (searching.length > 40) searching.take(40) + "..." else searching
                toolChip.text = "\uD83D\uDD0D Searching: $display"
            }
            activeTool != ToolMode.NONE -> {
                toolChipContainer.visibility = View.VISIBLE
                toolChip.text = "${activeTool.icon} ${activeTool.label}  ✕"
            }
            else -> {
                toolChipContainer.visibility = View.GONE
            }
        }
    }
    // ── Save / Load / Delete (server-backed) ─────────────────────────

    private fun showSaveDialog() {
        val editText = EditText(this).apply {
            hint = "Chat title"
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Save Chat")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val title = editText.text?.toString()?.trim().orEmpty()
                    .ifBlank { "Untitled Chat" }
                viewModel.saveCurrentSession(title)
                Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadSavedChat(thread: ChatThread) {
        viewModel.loadChat(thread.id)
        Toast.makeText(this, "Chat loaded", Toast.LENGTH_SHORT).show()
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun deleteSavedChat(thread: ChatThread) {
        viewModel.deleteChat( thread.id)
    }

    private fun startNewChat() {
        viewModel.clearMessages()
        viewModel.newServerSession()
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun formatTimestamp(epochSeconds: Long): String {
        if (epochSeconds <= 0) return ""
        val sdf = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(epochSeconds * 1000))
    }
}


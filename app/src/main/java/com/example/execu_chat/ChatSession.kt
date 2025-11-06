package com.example.execu_chat

class ChatSession {
    private val _turns = mutableListOf<Turn>()
    val turns: List<Turn> get() = _turns

    fun clear() {
        _turns.clear()
    }

    fun appendUser(msg: String) {
        _turns.add(Turn(Turn.Role.User, msg))
    }

    fun appendAssistant(msg: String) {
        _turns.add(Turn(Turn.Role.Assistant, msg))
    }

    fun appendSystem(msg: String) {
        _turns.add(Turn(Turn.Role.System, msg))
    }

    fun fullPrompt(): String = ChatFormatter.buildFullPrompt(_turns)

    fun fullTranscript(): String =
        _turns.joinToString("\n") { t -> "${t.role}: ${t.text}" }

    /** Rebuild turns from a saved transcript (very simple parser). */
    fun resetFromTranscript(text: String) {
        _turns.clear()

        var currentRole: Turn.Role? = null
        val currentText = StringBuilder()

        text.lines().forEach { line ->
            val trimmed = line.trim()

            when {
                trimmed.startsWith("User:") -> {
                    // Save previous turn if exists
                    if (currentRole != null && currentText.isNotEmpty()) {
                        _turns.add(Turn(currentRole, currentText.toString().trim()))
                    }
                    // Start new user turn
                    currentRole = Turn.Role.User
                    currentText.clear()
                    currentText.append(trimmed.removePrefix("User:").trim())
                }
                trimmed.startsWith("Assistant:") -> {
                    // Save previous turn if exists
                    if (currentRole != null && currentText.isNotEmpty()) {
                        _turns.add(Turn(currentRole, currentText.toString().trim()))
                    }
                    // Start new assistant turn
                    currentRole = Turn.Role.Assistant
                    currentText.clear()
                    currentText.append(trimmed.removePrefix("Assistant:").trim())
                }
                trimmed.startsWith("System:") -> {
                    // Save previous turn if exists
                    if (currentRole != null && currentText.isNotEmpty()) {
                        _turns.add(Turn(currentRole, currentText.toString().trim()))
                    }
                    // Start new system turn
                    currentRole = Turn.Role.System
                    currentText.clear()
                    currentText.append(trimmed.removePrefix("System:").trim())
                }
                trimmed.isNotBlank() && currentRole != null -> {
                    // Continuation of current turn
                    if (currentText.isNotEmpty()) currentText.append(' ')
                    currentText.append(trimmed)
                }
            }
        }

        // Don't forget the last turn
        if (currentRole != null && currentText.isNotEmpty()) {
            _turns.add(Turn(currentRole, currentText.toString().trim()))
        }
    }



}

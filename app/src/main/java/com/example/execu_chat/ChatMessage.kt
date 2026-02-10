package com.example.execu_chat


data class ChatMessage(
    val role: Role,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class Role { User, Assistant, System }
    val isUser: Boolean get() = role == Role.User

    fun toTurn(): Turn = Turn(
        role = when (role) {
            Role.User -> Turn.Role.User
            Role.Assistant -> Turn.Role.Assistant
            Role.System -> Turn.Role.System
        },
        text = text
    )
    companion object {
        fun fromTranscriptionLine(line: String): ChatMessage? {
            val trimmed = line.trim()
            return when {
                trimmed.startsWith("User:") -> ChatMessage(
                    Role.User,
                    trimmed.removePrefix("User:").trim()
                )
                trimmed.startsWith("Assistant:") -> ChatMessage(
                    Role.Assistant,
                    trimmed.removePrefix("Assistant:").trim()
                )
                trimmed.startsWith("System:") -> ChatMessage(
                    Role.System,
                    trimmed.removePrefix("System:").trim()
                )
                else -> null
            }
        }
    }
}
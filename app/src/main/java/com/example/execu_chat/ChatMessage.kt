package com.example.execu_chat


data class ChatMessage(
    val role: Role,
    val text: String,
    val thinkingContent: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class Role { User, Assistant, System }
    val isUser: Boolean get() = role == Role.User

    val cleanText: String get() =  extractCleanContent(text).second
    fun toTurn(): Turn = Turn(
        role = when (role) {
            Role.User -> Turn.Role.User
            Role.Assistant -> Turn.Role.Assistant
            Role.System -> Turn.Role.System
        },
        text = cleanText
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

        // Extract thinking and clean content from raw text
        fun extractCleanContent(rawText: String): Pair<String?, String> {
            val thinkingRegex = "<think>(.*?)</think>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val thinkingMatch = thinkingRegex.find(rawText)

            val thinking = thinkingMatch?.groupValues?.get(1)?.trim()
            val cleanContent = rawText
                .replace(thinkingRegex, "")
                .replace("<think>".toRegex(), "")
                .replace("</think>".toRegex(), "")
                .replace("\\n{3,}".toRegex(), "\n\n")
                .trim()

            return Pair(thinking, cleanContent)
        }
    }
}
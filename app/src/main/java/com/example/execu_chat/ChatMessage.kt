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
        /**
         * Extract just the thinking summary (model-generated).
         * Falls back to first 80 chars if no summary found.
         */
        fun extractThinkingSummary(rawText: String): String? {
            // First extract the thinking block
            val thinkingRegex = "<think>(.*?)</think>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val thinkingMatch = thinkingRegex.find(rawText) ?: return null
            val thinking = thinkingMatch.groupValues[1]

            // Then extract summary from within thinking
            val summaryRegex = "<summary>(.*?)</summary>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val summaryMatch = summaryRegex.find(thinking)

            return summaryMatch?.groupValues?.get(1)?.trim()
        }
    }
}
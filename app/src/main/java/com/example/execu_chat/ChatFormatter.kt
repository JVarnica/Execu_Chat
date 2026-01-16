package com.example.execu_chat

data class Turn(val role: Role, val text: String) {
    enum class Role { User, Assistant, System }
}

/**
 * Llama-3 Instruct chat template:
 *
 * <|begin_of_text|>
 * <|start_header_id|>system<|end_header_id|>
 *
 * {system text}<|eot_id|>
 * <|start_header_id|>user<|end_header_id|>
 *
 * {user text}<|eot_id|>
 * <|start_header_id|>assistant<|end_header_id|>
 *
 */
object ChatFormatter {
    val STOP_STRINGS = listOf("<|eot_id|", "<|end_of_text|>", "</s>")

    fun buildInitialPrompt(systemPrompt: String, userMessage: String): String {
        return buildString {
            // System prompt
            append("<|begin_of_text|>")
            append("<|start_header_id|>system<|end_header_id|>\n\n")
            append(systemPrompt)
            append("<|eot_id|>")

            // First user message
            append("<|start_header_id|>user<|end_header_id|>\n\n")
            append(userMessage.trim())
            append("<|eot_id|>")

            // Assistant header for response
            append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        }
    }
    fun buildFullPrompt(turns: List<Turn>): String {
        val sb = StringBuilder()
        sb.append("<|begin_of_text|>")

        val sys = turns.firstOrNull { it.role == Turn.Role.System }?.text ?: ""
        if (sys.isNotBlank()) {
            sb.append("<|start_header_id|>system<|end_header_id|>\n\n")
            sb.append(sys.trim())
            sb.append("<|eot_id|>")
        }

        // rest
        turns.filter { it.role != Turn.Role.System }.forEach { t ->
            when (t.role) {
                Turn.Role.User -> {
                    sb.append("<|start_header_id|>user<|end_header_id|>\n\n")
                        .append(t.text.trim()).append("<|eot_id|>")
                }

                Turn.Role.Assistant -> {
                    sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
                        .append(t.text.trim()).append("<|eot_id|>")
                }

                else -> {}
            }
        }
        // ask model to continue as assistant
        sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        return sb.toString()
    }
    fun buildDeltaFromUser(msg: String): String =
        "<|start_header_id|>user<|end_header_id|>\n\n" +
                msg.trim() +
                "<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"

    /** Remove control tokens and tidy whitespace, chunk-safe. */
    fun sanitizeChunk(raw: String): String {
        //p
        var s = raw
        // strip known special tokens
        s = s.replace("<\\|begin_of_text\\|>".toRegex(), "")
            .replace("<\\|start_header_id\\|>".toRegex(), "")
            .replace("<\\|end_header_id\\|>".toRegex(), "")
            .replace("<\\|assistant\\|>".toRegex(RegexOption.IGNORE_CASE), "")
            .replace("<\\|user\\|>".toRegex(RegexOption.IGNORE_CASE), "")
            .replace("<\\|system\\|>".toRegex(RegexOption.IGNORE_CASE), "")
            .replace("<\\|eot_id\\|>".toRegex(), "")
            .replace("<\\|end_of_text\\|>".toRegex(), "")
            .replace("</s>".toRegex(), "")

        // tidy spaces
        s = s.replace("[\\t\\x0B\\f\\r]".toRegex(), " ")
            .replace(" +".toRegex(), " ")
            .replace("\\n{3,}".toRegex(), "\n\n")
        return s
    }
    // Prefill prompts (used once during model load)
    fun getLlavaPresetPrompt(): String {
        return "A chat between a curious human and an artificial intelligence assistant. " +
                "The assistant gives helpful, detailed, and polite answers to the human's questions."
    }

    fun getQwenPresetPrompt(): String {
        return "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n"
    }

    // First turn prompts (used for the actual first message)
    fun getLlavaFirstTurnUserPrompt(): String {
        return "USER: ASSISTANT:"
    }

    fun getQwenFirstTurnUserPrompt(): String {
        return "<|im_start|>user\n<|im_end|>\n<|im_start|>assistant\n"
    }


}





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

    const val USER_PLACEHOLDER = "{{ user_prompt }}"
    const val SYSTEM_PLACEHOLDER = "{{ system_prompt }}"
    const val DEFAULT_SYS_PROMPT = "You are a helpful assistant, answer in a few sentences"
    fun getStopToken(modelType: ModelType): String {
        return when (modelType) {
            ModelType.LLAMA_3 -> "<|eot_id|>"
            ModelType.LLAVA -> "</s>"
            ModelType.QWEN_3 -> "<|endoftext|>"
        }
    }
    fun getStopStrings(modelType: ModelType): List<String> {
        val stopToken = getStopToken(modelType)
        return if (stopToken.isNotEmpty()) listOf(stopToken) else emptyList()
    }

    fun buildSystemPromptTemplate(modelType: ModelType): String {
        return when (modelType) {
            ModelType.LLAMA_3 -> {
                "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n" +
                        SYSTEM_PLACEHOLDER +
                        "<|eot_id|>"
            }
            ModelType.QWEN_3 -> {
                "<|im_start|>system\n$SYSTEM_PLACEHOLDER<|im_end|>\n"
            }
            else -> ""
        }
    }
    fun buildUserPrompt(modelType: ModelType, userText: String, thinkingMode: Boolean = false): String {
        return when (modelType) {
            ModelType.LLAMA_3 -> {
                "<|start_header_id|>user<|end_header_id|>\n" +
                        userText.trim() +
                        "<|eot_id|>" +
                        "<|start_header_id|>assistant<|end_header_id|>"
            }
            ModelType.QWEN_3 -> {
                val thinkToken = if (thinkingMode) "" else "<think>\n\n</think>\n\n\n"
                "<|im_start|>user\n" +
                        userText.trim() +
                        "\n<|im_end|>\n" +
                        "<|im_start|>assistant\n" +
                        thinkToken
            }
            ModelType.LLAVA -> {
                " USER: ${userText.trim()} ASSISTANT:"
            }
        }
    }

    fun buildFullPrompt(modelType: ModelType, turns: List<Turn>): String {
        val sb = StringBuilder()

        // Add begin token for Llama-3
        if (modelType == ModelType.LLAMA_3) {
            sb.append("<|begin_of_text|>")
        }

        // Add system prompt if present
        //val systemTurn = turns.firstOrNull { it.role == Turn.Role.System }
        //if (systemTurn != null && systemTurn.text.isNotBlank()) {
        //    sb.append(buildSystemPrompt(modelType, systemTurn.text))
        //}

        // Add conversation turns
        turns.filter { it.role != Turn.Role.System }.forEach { turn ->
            when (turn.role) {
                Turn.Role.User -> {
                    when (modelType) {
                        ModelType.LLAMA_3 -> {
                            sb.append("<|start_header_id|>user<|end_header_id|>\n")
                                .append(turn.text.trim())
                                .append("<|eot_id|>")
                        }

                        ModelType.QWEN_3 -> {
                            sb.append("<|im_start|>user\n")
                                .append(turn.text.trim())
                                .append("\n<|im_end|>\n")
                        }

                        ModelType.LLAVA -> {
                            sb.append(" USER: ${turn.text.trim()}")
                        }
                    }
                }

                Turn.Role.Assistant -> {
                    when (modelType) {
                        ModelType.LLAMA_3 -> {
                            sb.append("<|start_header_id|>assistant<|end_header_id|>\n")
                                .append(turn.text.trim())
                                .append("<|eot_id|>")
                        }

                        ModelType.QWEN_3 -> {
                            sb.append("<|im_start|>assistant\n")
                                .append(turn.text.trim())
                                .append("\n<|im_end|>\n")
                        }

                        ModelType.LLAVA -> {
                            sb.append(" ASSISTANT: ${turn.text.trim()}")
                        }
                    }
                }

                else -> {}
            }
        }

        // Add assistant header to prompt for next response
        when (modelType) {
            ModelType.LLAMA_3 -> {
                sb.append("<|start_header_id|>assistant<|end_header_id|>")
            }

            ModelType.QWEN_3 -> {
                sb.append("<|im_start|>assistant\n")
            }

            ModelType.LLAVA -> {
                sb.append(" ASSISTANT:")
            }
        }
        return sb.toString()
    }
    fun buildDeltaFromUser(modelType: ModelType, msg: String, thinkingMode: Boolean = false): String {
        return buildUserPrompt(modelType, msg, thinkingMode)
    }
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
            .replace("</s>".toRegex(), "")
            .replace("<\\|im_start\\|>".toRegex(), "")
            .replace("<\\|im_end\\|>".toRegex(), "")
            .replace("<\\|end_of_text\\|>".toRegex(), "")

        s = s.replace("<\\|assistant\\|>".toRegex(RegexOption.IGNORE_CASE), "")
            .replace("<\\|user\\|>".toRegex(RegexOption.IGNORE_CASE), "")
            .replace("<\\|system\\|>".toRegex(RegexOption.IGNORE_CASE), "")
        // tidy spaces
        s = s.replace("[\\t\\x0B\\f\\r]".toRegex(), " ")
            .replace(" +".toRegex(), " ")
            .replace("\\n{3,}".toRegex(), "\n\n")
        return s
    }
    // Prefill prompts (used once during model load)
    fun getLlavaPresetPrompt(): String {
        return "A chat between a curious human and an artificial intelligence assistant. " +
                "The assistant gives helpful, detailed, and polite answers to the human's questions. USER: "
    }
    fun getLlavaFirstTurnUserPrompt(): String {
        return "USER: $USER_PLACEHOLDER ASSISTANT:"
    }
}





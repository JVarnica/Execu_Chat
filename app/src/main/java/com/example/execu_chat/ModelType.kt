package com.example.execu_chat

import org.pytorch.executorch.extension.llm.LlmModule

enum class ModelType {
    LLAMA_3,
    QWEN_3,
    LLAVA;

    fun isMultiModal(): Boolean {
        return this == LLAVA
    }
    fun getModelType(): Int {
        return if (isMultiModal()) {
            LlmModule.MODEL_TYPE_MULTIMODAL
        } else {
            LlmModule.MODEL_TYPE_TEXT
        }
    }
}
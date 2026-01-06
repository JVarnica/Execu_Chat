// ModelConfig.kt
package com.example.execu_chat

data class ModelConfig(
    val modelType: ModelType,
    val modelFileName: String,
    val tokenizerFileName: String,
    val temperature: Float = 0.8f
)

object ModelConfigs {
    val LLAVA = ModelConfig(
        // rss approx 3.6gb when trying to process image logic still there in case of big phone
        modelType = ModelType.LLAVA,
        modelFileName = "llava_xnn.pte",
        tokenizerFileName = "tokenizer.json"
    )

    val LLAMA3 = ModelConfig(
        modelType = ModelType.LLAMA_3,
        modelFileName = "llama1B_4wt.pte",
        tokenizerFileName = "tokenizer.model"
    )

    val QWEN = ModelConfig(
        modelType = ModelType.QWEN_3,
        modelFileName = "qwen_vl.pte",
        tokenizerFileName = "qwen_tokenizer.model"
    )

    val ALL = listOf(LLAVA, LLAMA3, QWEN)

    fun getDisplayName(modelType: ModelType): String {
        return when (modelType) {
            ModelType.LLAVA -> "Llava 1.5 (Vision)"
            ModelType.LLAMA_3 -> "Llama 3 (Text)"
            ModelType.QWEN_3 -> "Qwen-VL (Vision)"
        }
    }
}



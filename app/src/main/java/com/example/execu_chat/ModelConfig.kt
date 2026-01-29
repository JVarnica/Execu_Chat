// ModelConfig.kt
package com.example.execu_chat

data class ModelConfig(
    val modelType: ModelType,
    val modelFileName: String,
    val tokenizerFileName: String,
    val temperature: Float = 0.4f,
    val displayName: String
)
object ModelConfigs {
    val LLAVA_B = ModelConfig(
        // rss approx 3.6gb when trying to process image logic still there in case of big phone
        modelType = ModelType.LLAVA,
        modelFileName = "llava_xnn.pte",
        tokenizerFileName = "tokenizer.json",
        displayName = "llava_small"
    )
    val LLAVA = ModelConfig(
        modelType = ModelType.LLAVA,
        modelFileName = "llava.pte",
        tokenizerFileName = "llava_tokenizer.model",
        displayName = "llava"
    )
    val LLAMA_S = ModelConfig(
        modelType = ModelType.LLAMA_3,
        modelFileName = "llama1B_4w4d.pte",
        tokenizerFileName = "llama_tokenizer.json",
        displayName = "llama_1B"
    )

    val LLAMA3 = ModelConfig(
        modelType = ModelType.LLAMA_3,
        modelFileName = "Llama3.2-3B-QLORA_8da4w.pte",
        tokenizerFileName = "llama_tokenizer.json",
        displayName = "llama_3B"
    )

    val QWEN = ModelConfig(
        modelType = ModelType.QWEN_3,
        modelFileName = "qwen3_4b_xnnpack.pte",
        tokenizerFileName = "qwen_tokenizer.json",
        displayName = "qwen3_4b"
    )

    val ALL = listOf(LLAVA, LLAVA_B, LLAMA3, LLAMA_S, QWEN)
}



// ModelConfig.kt
// setting up model and tokenizer to one structure so can switch models.
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
    val LLAMA_S_X = ModelConfig(
        modelType = ModelType.LLAMA_3,
        modelFileName = "llama1B_8da4w_xnn.pte",
        tokenizerFileName = "llama_tokenizer.json",
        displayName = "llama1B_xnn.pte"
    )
    val LLAVA = ModelConfig(
        modelType = ModelType.LLAVA,
        modelFileName = "llava.pte",
        tokenizerFileName = "llava_tokenizer.model",
        displayName = "llava"
    )
    val LLAMA_S_V = ModelConfig(
        modelType = ModelType.LLAMA_3,
        modelFileName = "llama1B_4w4d.pte",
        tokenizerFileName = "llama_tokenizer.json",
        displayName = "llama_1B"
    )

    val LLAMA3_V = ModelConfig(
        modelType = ModelType.LLAMA_3,
        modelFileName = "llama3B_8da4w.pte",
        tokenizerFileName = "llama_tokenizer.json",
        displayName = "llama_3B_V"
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
    val ALL = listOf(LLAVA, LLAVA_B, LLAMA3, LLAMA_S_X, LLAMA_S_V, LLAMA3_V, QWEN)
}



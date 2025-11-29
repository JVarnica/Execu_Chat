/*package com.example.execu_chat

import org.pytorch.executorch.EValue
import org.pytorch.executorch.Tensor
import org.pytorch.executorch.Module
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AsrEngine(
    private val preprocPath: String,
    private val whisperPath: String,
    private val decodeStartId: Int,
) {
    private val preprocModule: Module = Module.load(preprocPath)
    priuvate val whisperModule: Module = Module.load(whisperPath)

    /**
     * pcmFloats: mono 16 kHz audio, normalized to [-1, 1], as in your Python test.
     */
    fun runOnce(pcmFloats: FloatArray): Int {
        // 1) Audio -> mel features via preproc .pte
        val audioTensor = Tensor.fromBlob(
            pcmFloats,
            longArrayOf(pcmFloats.size.toLong()) // [T]
        )

        val melEValues = preprocModule.forward(EValue.from(audioTensor))
        val melTensor = melEValues[0].toTensor()        // shape [1, 80, 3000] (or similar)

        // 2) First decode step: BOS token only
        val decoderIds = longArrayOf(decoderStartId.toLong())
        val decoderTensor = Tensor.fromBlob(
            decoderIds,
            longArrayOf(1L, 1L) // [1, 1]
        )

        val outEValues = whisperModule.forward(
            EValue.from(melTensor),
            EValue.from(decoderTensor)
        )
        val logitsTensor = outEValues[0].toTensor()     // [1, 1, vocab]
        val logits = logitsTensor.dataAsFloatArray

        // 3) Argmax over vocab dimension
        val vocabSize = logits.size // since [1,1,V], array is just flat V
        var maxIdx = 0
        var maxVal = logits[0]
        for (i in 1 until vocabSize) {
            val v = logits[i]
            if (v > maxVal) {
                maxVal = v
                maxIdx = i
            }
        }
        return maxIdx // token id for first step
    }
}

fun pcm16ToFloatArray(pcmBytes: ByteArray): FloatArray {
    val totalSamples = pcmBytes.size / 2
    val floats = FloatArray(totalSamples)
    val bb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until totalSamples) {
        val s = bb.short.toInt()
        floats[i] = if (s < 0) s / 32768.0f else s / 32767.0f
    }
    return floats
}

 */

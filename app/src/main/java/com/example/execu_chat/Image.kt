// app/src/main/java/com/example/execu_chat/Image.kt
package com.example.execu_chat

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.get
import androidx.core.graphics.scale

class ETImage(
    private val contentResolver: ContentResolver,
    val uri: Uri,
    sideSize: Int
) {
    val width: Int
    val height: Int

    private val bytes: ByteArray

    init {
        val bitmap = resizeImage(uri, sideSize)
        width = bitmap.width
        height = bitmap.height

        val hw = width * height
        bytes = ByteArray(hw * 3)

        // planar RGB: [R...][G...][B...]
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap[x, y]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                val idx = y * width + x
                bytes[idx] = r.toByte()
                bytes[idx + hw] = g.toByte()
                bytes[idx + 2 * hw] = b.toByte()
            }
        }

        bitmap.recycle()
    }

    fun getBytes(): ByteArray = bytes.copyOf()

    // unsigned 0..255 ints (what LLaVA path usually expects)
    val ints: IntArray
        get() = IntArray(bytes.size) { i -> bytes[i].toInt() and 0xFF }

    // normalized [-1, 1] floats
    val floats: FloatArray
        get() = FloatArray(bytes.size) { i ->
            (((bytes[i].toInt() and 0xFF) / 255.0f) - 0.5f) / 0.5f
        }

    private fun resizeImage(uri: Uri, sideSize: Int): Bitmap {
        val bitmap = contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Failed to open input stream for image URI" }
            BitmapFactory.decodeStream(input)
        } ?: throw RuntimeException("Failed to decode image")

        return bitmap.scale(sideSize, sideSize)
    }
}

// app/src/main/java/com/example/execu_chat/Image.kt
package com.example.execu_chat

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.graphics.get
import android.graphics.Color
import java.io.FileNotFoundException
import java.lang.RuntimeException

class ETImage(
    private val contentResolver: ContentResolver,
    val uri: Uri,
    sideSize: Int
) {
    var width: Int = 0
        private set
    var height: Int = 0
        private set

    val bytes: ByteArray = getBytesUri(uri, sideSize)
    // unsigned 0..255 ints (what LLaVA path usually expects)
    fun getInts(): IntArray {
        return IntArray(bytes.size) { i -> bytes[i].toInt() and 0xFF }
    }

    // normalized [-1, 1] floats
    val floats: FloatArray
        get() = FloatArray(bytes.size) { i ->
            (((bytes[i].toInt() and 0xFF) / 255.0f) - 0.5f) / 0.5f
        }

    private fun getBytesUri(uri: Uri, sideSize: Int): ByteArray {
        try {
            val bitmap = resizeImage(uri, sideSize)

            width = bitmap.width
            height = bitmap.height

            val hw = width * height
            val rgbValues = ByteArray(hw * 3)

            // planar RGB: [R...][G...][B...]
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val pixel = bitmap[x, y]
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)

                    val idx = y * width + x
                    rgbValues[idx] = r.toByte()
                    rgbValues[idx + hw] = g.toByte()
                    rgbValues[idx + 2 * hw] = b.toByte()
                }
            }
            bitmap.recycle()
            return rgbValues
        } catch (e: FileNotFoundException) {
            throw kotlin.RuntimeException(e)
        }
    }
    private fun resizeImage(uri: Uri, sideSize: Int): Bitmap {
        val inputStream = contentResolver.openInputStream(uri) ?: throw RuntimeException("Failed to open input stream")
        val bitmap = inputStream.use {
            BitmapFactory.decodeStream(it) } ?: throw RuntimeException("Failed to decode image: $uri")

        return Bitmap.createScaledBitmap(bitmap, sideSize, sideSize, false)
    }
}

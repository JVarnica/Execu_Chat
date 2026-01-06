
package com.example.execu_chat


import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
//Agnostic just storing each transcript in a file in filesdir
data class ChatThread(val id: String, val title: String, val preview: String, val path: String)

object ChatStore {
    private const val DIR = "threads"

    private fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    fun save(ctx: Context, fullTranscript: String): ChatThread {
        val id = System.currentTimeMillis().toString()
        val file = File(dir(ctx), "$id.txt")
        file.writeText(fullTranscript)

        val title = titleOf(fullTranscript)
        val preview = previewOf(fullTranscript)
        return ChatThread(id, title, preview, file.absolutePath)
    }
    fun update(ctx: Context, chatId: String, fullTranscript: String): ChatThread {
        val file = File(dir(ctx), "$chatId.txt")
        file.writeText(fullTranscript)

        val title = titleOf(fullTranscript)
        val preview = previewOf(fullTranscript)
        return ChatThread(chatId, title, preview, file.absolutePath)
    }
    fun delete(ctx: Context, chatId: String): Boolean {
        val file = File(dir(ctx), "$chatId.txt")
        return file.delete()
    }
    fun list(ctx: Context): List<ChatThread> =
        dir(ctx).listFiles()
            ?.sortedByDescending { it.nameWithoutExtension }
            ?.map { f ->
                val text = runCatching { f.readText() }.getOrDefault("")
                ChatThread(
                    id = f.nameWithoutExtension,
                    title = titleOf(text),
                    preview = previewOf(text),
                    path = f.absolutePath
                )
            } ?: emptyList()

    fun load(thread: ChatThread): String =
        runCatching { File(thread.path).readText() }.getOrDefault("")

    private fun titleOf(txt: String): String {
        val first = txt.lineSequence().firstOrNull { it.isNotBlank() } ?: "(empty)"
        val clean = first.replace("\\s+".toRegex(), " ").trim()
        return if (clean.length > 40) clean.take(40) + "…" else clean
    }

    private fun previewOf(txt: String): String {
        val joined = txt.replace("\\s+".toRegex(), " ").trim()
        return if (joined.length > 60) joined.take(60) + "…" else joined
    }

    fun stamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date())
    }
}

package com.example.execu_chat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream


object AssetMover {

    suspend fun copyAssetToFiles(context: Context, name: String): String =
        withContext(Dispatchers.IO) {
            existInFilesDir(context, name)?.let { return@withContext it.absolutePath }
            val outFile = File(context.filesDir, name)
            context.assets.open(name).use { input ->
                FileOutputStream(outFile, false).use { output ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        output.write(buf, 0, n)
                    }
                    output.flush()
                }
            }
            outFile.absolutePath
        }
    fun existInFilesDir(ctx: Context, name: String): File? {
        val f = File(ctx.filesDir, name)
        return if (f.isFile && f.length() > 0L) f else null
    }
    /** Copy an entire asset directory tree into filesDir. Returns the root path. */
    suspend fun copyAssetDirToFiles(context: Context, assetDir: String): String =
        withContext(Dispatchers.IO) {
            val outRoot = File(context.filesDir, assetDir)
            val readyMarker = File(outRoot, ".ready")
            //fast path
            if (readyMarker.exists() && outRoot.isDirectory) {
                return@withContext outRoot.absolutePath
            }
            //first time
            if (!outRoot.exists()) outRoot.mkdirs()
            copyDirRecursively(context, assetDir, outRoot)

            readyMarker.writeText("ok")
            return@withContext outRoot.absolutePath
        }

    private fun copyDirRecursively(context: Context, assetPath: String, outDir: File) {
        val assetManager = context.assets
        val children = assetManager.list(assetPath) ?: return

        if (children.isEmpty()) {
            // leaf node → copy file
            val outFile = File(outDir, assetPath.substringAfterLast('/'))
            if (!outFile.exists()) {
                assetManager.open(assetPath).use { input ->
                    FileOutputStream(outFile, false).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        } else {
            // directory → descend
            val dirName = assetPath.substringAfterLast('/')
            val nextOut = if (dirName == assetPath) outDir else File(outDir, dirName).apply { mkdirs() }
            for (child in children) {
                val childPath = "$assetPath/$child"
                copyDirRecursively(context, childPath, nextOut)
            }
        }
    }
}
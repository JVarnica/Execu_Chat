package com.example.execu_chat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import android.util.Log


object AssetMover {

    fun getModelsDirectory(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

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
    suspend fun copyAssetToModelsDir(context: Context, assetName: String): String =
        withContext(Dispatchers.IO) {
            val modelsDir = getModelsDirectory(context)
            val outFile = File(modelsDir, assetName)

            // Skip if already exists and is valid
            if (outFile.exists() && outFile.length() > 0L) {
                Log.d("AssetMover", "$assetName already exists in models directory")
                return@withContext outFile.absolutePath
            }

            Log.d("AssetMover", "Copying $assetName to models directory...")
            context.assets.open(assetName).use { input ->
                FileOutputStream(outFile, false).use { output ->
                    val buf = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        output.write(buf, 0, n)
                        total += n
                        if (total % (1024 * 1024) == 0L) {
                            Log.d("AssetMover", "Copied ${total / (1024 * 1024)} MB...")
                        }
                    }
                    output.flush()
                }
            }
            Log.d("AssetMover", "Finished copying $assetName")
            outFile.absolutePath
        }
    fun modelExists(context: Context, modelName: String): Boolean {
        val file = File(getModelsDirectory(context), modelName)
        return file.exists() && file.length() > 0L
    }
    fun getModelFile(context: Context, modelName: String): File? {
        val file = File(getModelsDirectory(context), modelName)
        return if (file.exists() && file.length() > 0L) file else null
    }

    /**
     * List all .pte model files in the models directory.
     */
    fun listModelFiles(context: Context): List<File> {
        return getModelsDirectory(context)
            .listFiles { file -> file.extension == "pte" && file.length() > 0L }
            ?.sortedBy { it.name }
            ?: emptyList()
    }
    fun deleteModel(context: Context, modelName: String): Boolean {
        val file = File(getModelsDirectory(context), modelName)
        return if (file.exists()) file.delete() else false
    }
    fun getTotalModelsSize(context: Context): Long {
        val modelsDir = getModelsDirectory(context)
        return modelsDir.walkTopDown()
            .filter { it.isFile }
            .map { it.length() }
            .sum() / (1024 * 1024) // Convert to MB
    }
    suspend fun copyAssetModelDirToModels(context: Context, assetDir: String): String =
        withContext(Dispatchers.IO) {
            val modelsDir = getModelsDirectory(context)
            val outRoot = File(modelsDir, assetDir)
            val readyMarker = File(outRoot, ".ready")

            // Fast path - already copied
            if (readyMarker.exists() && outRoot.isDirectory) {
                return@withContext outRoot.absolutePath
            }

            // First time - copy everything
            if (!outRoot.exists()) outRoot.mkdirs()
            copyDirRecursively(context, assetDir, outRoot)

            readyMarker.writeText("ok")
            Log.d("AssetMover", "Copied model directory $assetDir to external storage")
            return@withContext outRoot.absolutePath
        }
}
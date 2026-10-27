package com.blecky.assistant.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream

/** Downloads the small offline English model used only for spotting "Hey Blecky". */
object ModelInstaller {
    private const val URL_ZIP = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private const val FOLDER = "vosk-model-small-en-us-0.15"

    private fun root(context: Context) = File(context.filesDir, "vosk")
    private fun marker(context: Context) = File(root(context), ".installed")

    fun modelDir(context: Context) = File(root(context), FOLDER)

    fun isInstalled(context: Context) = marker(context).exists()

    suspend fun install(context: Context, progress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val root = root(context)
        root.deleteRecursively()
        root.mkdirs()
        val zip = File(context.cacheDir, "vosk-model.zip")
        val connection = URL(URL_ZIP).openConnection()
        val total = connection.contentLengthLong
        connection.getInputStream().use { input ->
            zip.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    copied += n
                    if (total > 0) progress((copied * 100 / total).toInt())
                }
            }
        }
        val rootPath = root.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { entries ->
            while (true) {
                val entry = entries.nextEntry ?: break
                val out = File(root, entry.name)
                if (!out.canonicalPath.startsWith(rootPath)) throw SecurityException("Bad entry in model zip: ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { entries.copyTo(it) }
                }
            }
        }
        zip.delete()
        check(modelDir(context).isDirectory) { "Model download was incomplete." }
        marker(context).createNewFile()
    }
}

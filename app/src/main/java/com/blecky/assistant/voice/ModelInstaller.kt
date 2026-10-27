package com.blecky.assistant.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.SpeakerModel
import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Downloads and loads the offline models: a small English model that spots the wake word, and a
 * speaker model that checks it's the owner talking.
 */
object ModelInstaller {
    private class Download(val label: String, val url: String, val folder: String)

    private val SPEECH = Download(
        "offline ear", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", "vosk-model-small-en-us-0.15",
    )
    private val SPEAKER = Download(
        "voice recognition", "https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip", "vosk-model-spk-0.4",
    )

    private var speechModel: Model? = null
    private var speakerModel: SpeakerModel? = null

    private fun root(context: Context) = File(context.filesDir, "vosk")
    private fun dir(context: Context, d: Download) = File(root(context), d.folder)
    private fun marker(context: Context, d: Download) = File(root(context), ".${d.folder}")

    fun isInstalled(context: Context) = listOf(SPEECH, SPEAKER).all { marker(context, it).exists() }

    /** Loaded once and kept for the life of the process. Call off the main thread. */
    @Synchronized
    fun speech(context: Context): Model =
        speechModel ?: Model(dir(context, SPEECH).absolutePath).also { speechModel = it }

    @Synchronized
    fun speaker(context: Context): SpeakerModel =
        speakerModel ?: SpeakerModel(dir(context, SPEAKER).absolutePath).also { speakerModel = it }

    suspend fun install(context: Context, progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        listOf(SPEECH, SPEAKER).filterNot { marker(context, it).exists() }.forEach { d ->
            dir(context, d).deleteRecursively()
            unzip(download(context, d, progress), root(context))
            check(dir(context, d).isDirectory) { "The ${d.label} download was incomplete." }
            marker(context, d).createNewFile()
        }
    }

    private fun download(context: Context, d: Download, progress: (String) -> Unit): File {
        val zip = File(context.cacheDir, "${d.folder}.zip")
        val connection = URL(d.url).openConnection()
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
                    if (total > 0) progress("Downloading Blecky's ${d.label} (${total / 1_000_000} MB): ${copied * 100 / total}%")
                }
            }
        }
        return zip
    }

    private fun unzip(zip: File, root: File) {
        root.mkdirs()
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
    }
}

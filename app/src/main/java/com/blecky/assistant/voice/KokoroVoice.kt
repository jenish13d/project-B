package com.blecky.assistant.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * Kokoro-82M (github.com/hexgrad/kokoro), a small open neural voice that sounds human, run offline
 * with sherpa-onnx. About 100 MB, downloaded once.
 */
class KokoroVoice(private val context: Context) {
    private var tts: OfflineTts? = null

    @Volatile
    private var current: PcmPlayer? = null

    fun isInstalled() = File(root(), MARKER).exists()

    suspend fun install(progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        val root = root()
        root.deleteRecursively()
        root.mkdirs()
        val archive = File(context.cacheDir, "kokoro.tar.bz2")
        val connection = URL(URL_ARCHIVE).openConnection()
        val total = connection.contentLengthLong
        connection.getInputStream().use { input ->
            archive.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    copied += n
                    if (total > 0) progress("Downloading Blecky's natural voice (${total / 1_000_000} MB): ${copied * 100 / total}%")
                }
            }
        }
        progress("Unpacking the natural voice…")
        val rootPath = root.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val out = File(root, entry.name)
                if (!out.canonicalPath.startsWith(rootPath)) throw SecurityException("Bad entry in voice archive: ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it) }
                }
            }
        }
        archive.delete()
        check(File(dir(), "model.int8.onnx").exists()) { "The voice download was incomplete." }
        File(root, MARKER).createNewFile()
    }

    /** Speaks [text] with Kokoro speaker [speakerId]; blocks until it has been heard. */
    fun speak(text: String, speakerId: Int, speed: Float = 1.0f) {
        val engine = engine()
        val player = PcmPlayer(engine.sampleRate(), floatSamples = true)
        current = player
        // Synthesis runs sentence by sentence; play each one while the next is being made.
        val queue = LinkedBlockingQueue<FloatArray>()
        val writer = thread(name = "blecky-voice") {
            while (true) {
                val chunk = queue.take()
                if (chunk.isEmpty()) break
                player.write(chunk)
            }
        }
        try {
            engine.generateWithCallback(text, speakerId, speed) { samples ->
                queue.put(samples.copyOf())
                if (player.stopped) 0 else 1
            }
        } finally {
            queue.put(FloatArray(0))
            writer.join()
            player.finish()
            current = null
        }
    }

    fun stop() {
        current?.stop()
    }

    @Synchronized
    private fun engine(): OfflineTts = tts ?: run {
        val dir = dir().absolutePath
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = "$dir/model.int8.onnx",
                    voices = "$dir/voices.bin",
                    tokens = "$dir/tokens.txt",
                    dataDir = "$dir/espeak-ng-data",
                ),
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
                debug = false,
                provider = "cpu",
            ),
            maxNumSentences = 1,
        )
        OfflineTts(null, config).also { tts = it }
    }

    private fun root() = File(context.filesDir, "kokoro")
    private fun dir() = File(root(), FOLDER)

    companion object {
        private const val URL_ARCHIVE =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-en-v0_19.tar.bz2"
        private const val FOLDER = "kokoro-int8-en-v0_19"
        private const val MARKER = ".installed"

        /** Kokoro v0.19 female speakers (id to name), friendliest first. */
        val VOICES = listOf(1 to "Bella", 4 to "Sky", 3 to "Sarah", 7 to "Emma (British)", 8 to "Isabella (British)", 2 to "Nicole")
    }
}

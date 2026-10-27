package com.blecky.assistant.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * Free, offline neural voices from GitHub, run on the phone with sherpa-onnx
 * (github.com/k2-fsa/sherpa-onnx). Piper voices are fast enough for budget phones;
 * Kokoro sounds the most human but needs a fast processor.
 */
class NeuralVoices(private val context: Context) {
    enum class Kind { PIPER, KOKORO, SUPERTONIC }

    class Pack(
        val id: String,
        val label: String,
        val folder: String,
        val kind: Kind,
        val sizeMb: Int,
        val speakerId: Int = 0,
        val language: String = "en",
        /** Can also read Hindi (Supertonic speaks 31 languages, Hindi among them). */
        val speaksHindi: Boolean = false,
    ) {
        val url get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$folder.tar.bz2"
    }

    private var tts: OfflineTts? = null
    private var loadedFolder: String? = null

    @Volatile
    private var current: PcmPlayer? = null

    fun pack(id: String): Pack = PACKS.firstOrNull { it.id == id } ?: PACKS.first()

    fun isInstalled(pack: Pack) = File(root(), ".${pack.folder}").exists()

    fun installed(): List<Pack> = PACKS.filter(::isInstalled)

    suspend fun install(pack: Pack, progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        val root = root()
        root.mkdirs()
        File(root, pack.folder).deleteRecursively()
        val archive = File(context.cacheDir, "${pack.folder}.tar.bz2")
        val connection = URL(pack.url).openConnection()
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
                    if (total > 0) progress("Downloading ${pack.label} (${total / 1_000_000} MB): ${copied * 100 / total}%")
                }
            }
        }
        progress("Unpacking ${pack.label}…")
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
        check(modelFile(pack) != null) { "The voice download was incomplete." }
        // Kokoro voices share one download, so mark all of them installed.
        PACKS.filter { it.folder == pack.folder }.forEach { File(root, ".${it.folder}").createNewFile() }
    }

    /** Speaks [text] with [pack] in [language] ("en" or "hi"); blocks until it has been heard. */
    fun speak(text: String, pack: Pack, speed: Float = 1.0f, language: String = "en") {
        val engine = engine(pack)
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
            val onChunk: (FloatArray) -> Int = { samples ->
                queue.put(samples.copyOf())
                if (player.stopped) 0 else 1
            }
            if (pack.kind == Kind.SUPERTONIC) {
                // Supertonic speaks a little slowly by default; 8 steps is its quality setting.
                val config = GenerationConfig(sid = pack.speakerId, speed = speed * 1.15f, numSteps = 8, extra = mapOf("lang" to language))
                engine.generateWithConfigAndCallback(text, config, onChunk)
            } else {
                engine.generateWithCallback(text, pack.speakerId, speed, onChunk)
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
    private fun engine(pack: Pack): OfflineTts {
        tts?.takeIf { loadedFolder == pack.folder }?.let { return it }
        // Keep only one voice in memory; budget phones don't have RAM to spare.
        tts?.release()
        tts = null
        val dir = File(root(), pack.folder).absolutePath
        val model = modelFile(pack)?.absolutePath ?: error("Voice ${pack.label} isn't installed")
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val modelConfig = when (pack.kind) {
            Kind.KOKORO -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = model,
                    voices = "$dir/voices.bin",
                    tokens = "$dir/tokens.txt",
                    dataDir = "$dir/espeak-ng-data",
                ),
                numThreads = threads,
                debug = false,
                provider = "cpu",
            )
            Kind.SUPERTONIC -> OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = "$dir/duration_predictor.int8.onnx",
                    textEncoder = "$dir/text_encoder.int8.onnx",
                    vectorEstimator = "$dir/vector_estimator.int8.onnx",
                    vocoder = "$dir/vocoder.int8.onnx",
                    ttsJson = "$dir/tts.json",
                    unicodeIndexer = "$dir/unicode_indexer.bin",
                    voiceStyle = "$dir/voice.bin",
                ),
                numThreads = threads,
                debug = false,
                provider = "cpu",
            )
            Kind.PIPER -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model,
                    tokens = "$dir/tokens.txt",
                    dataDir = "$dir/espeak-ng-data",
                ),
                numThreads = threads,
                debug = false,
                provider = "cpu",
            )
        }
        return OfflineTts(null, OfflineTtsConfig(model = modelConfig, maxNumSentences = 1)).also {
            tts = it
            loadedFolder = pack.folder
        }
    }

    private fun modelFile(pack: Pack): File? =
        File(root(), pack.folder).listFiles()?.firstOrNull { it.name.endsWith(".onnx") }

    private fun root() = File(context.filesDir, "voices")

    companion object {
        private const val KOKORO = "kokoro-int8-en-v0_19"
        private const val SUPERTONIC = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"

        val PACKS = listOf(
            // Supertonic 3 (github.com/supertone-inc/supertonic, MIT): natural and about 5x faster
            // than real time on two CPU cores in testing, so it suits budget phones. Voices 1-5 are female.
            Pack("st-f4", "Supertonic Zoe (natural, fast, also Hindi) ★", SUPERTONIC, Kind.SUPERTONIC, 129, speakerId = 3, speaksHindi = true),
            Pack("st-f2", "Supertonic Maya (natural, fast, also Hindi)", SUPERTONIC, Kind.SUPERTONIC, 129, speakerId = 1, speaksHindi = true),
            Pack("st-f1", "Supertonic Ava (natural, fast, also Hindi)", SUPERTONIC, Kind.SUPERTONIC, 129, speakerId = 0, speaksHindi = true),
            Pack("st-f3", "Supertonic Lily (natural, fast, also Hindi)", SUPERTONIC, Kind.SUPERTONIC, 129, speakerId = 2, speaksHindi = true),
            Pack("st-f5", "Supertonic Iris (natural, fast, also Hindi)", SUPERTONIC, Kind.SUPERTONIC, 129, speakerId = 4, speaksHindi = true),
            Pack("jenny", "Jenny, British (fast)", "vits-piper-en_GB-jenny_dioco-medium", Kind.PIPER, 67),
            Pack("amy", "Amy, American (fast)", "vits-piper-en_US-amy-medium", Kind.PIPER, 67),
            Pack("hannah", "Hannah, American (fast)", "vits-piper-en_US-hfc_female-medium", Kind.PIPER, 67),
            Pack("bella", "Bella, premium (needs a fast phone)", KOKORO, Kind.KOKORO, 103, speakerId = 1),
            Pack("sky", "Sky, premium (needs a fast phone)", KOKORO, Kind.KOKORO, 103, speakerId = 4),
            Pack("emma", "Emma, British premium (needs a fast phone)", KOKORO, Kind.KOKORO, 103, speakerId = 7),
            Pack("priyamvada", "Priyamvada, Hindi (fast)", "vits-piper-hi_IN-priyamvada-medium", Kind.PIPER, 67, language = "hi"),
        )
    }
}

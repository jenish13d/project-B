package com.blecky.assistant.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.blecky.assistant.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** Speech in (Android speech recognizer) and speech out (text-to-speech). */
class VoiceIO(private val context: Context, private val settings: Settings) {
    private val ttsReady = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private lateinit var tts: TextToSpeech
    val neural = NeuralVoices(context)
    private val elevenLabs = ElevenLabsVoice()

    @Volatile
    private var interrupted = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) applyVoice()
            ttsReady.complete(status == TextToSpeech.SUCCESS)
        }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) { pending.remove(utteranceId)?.complete(Unit) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) { pending.remove(utteranceId)?.complete(Unit) }
            override fun onStop(utteranceId: String, interrupted: Boolean) { pending.remove(utteranceId)?.complete(Unit) }
        })
    }

    /** English voices installed on the phone, friendly female ones first. */
    private fun englishVoices(): List<Voice> =
        (runCatching { tts.voices }.getOrNull() ?: emptySet())
            .filter { it.locale.language == "en" && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
            .sortedWith(compareBy({ femaleRank(it.name) }, { it.isNetworkConnectionRequired }, { it.name }))

    private fun applyVoice() {
        val voices = englishVoices()
        val chosen = voices.firstOrNull { it.name == settings.voiceName } ?: voices.firstOrNull()
        chosen?.let { tts.voice = it }
        // A touch brighter and quicker than the default reads as younger and friendlier.
        tts.setPitch(1.08f)
        tts.setSpeechRate(1.03f)
    }

    /** The chosen offline voice if it's downloaded, otherwise any downloaded one. */
    fun neuralPack(): NeuralVoices.Pack? {
        val chosen = neural.pack(settings.voicePack)
        return if (neural.isInstalled(chosen)) chosen else neural.installed().firstOrNull()
    }

    /** The voice Blecky speaks with now, for the settings screen. */
    fun currentVoiceName(): String = when {
        settings.elevenLabsKey.isNotBlank() -> "ElevenLabs (cloud)"
        neuralPack() != null -> neuralPack()!!.label
        else -> "Phone voice (basic)"
    }

    /** Switches to the next voice of the current engine and returns its name. */
    suspend fun nextVoice(): String? {
        val installed = neural.installed()
        if (installed.isNotEmpty()) {
            val index = installed.indexOfFirst { it.id == settings.voicePack }
            val next = installed[(index + 1) % installed.size]
            settings.voicePack = next.id
            return next.label
        }
        if (!ttsReady.await()) return null
        val voices = englishVoices()
        if (voices.isEmpty()) return null
        val current = voices.indexOfFirst { it.name == tts.voice?.name }
        val next = voices[(current + 1) % voices.size]
        settings.voiceName = next.name
        applyVoice()
        return next.name
    }

    /**
     * Speaks [text] and returns once it has finished. Uses the most natural voice available:
     * ElevenLabs if the user added a key, then the offline Kokoro voice, then the phone's own.
     */
    suspend fun speak(text: String) {
        val spoken = SpeechText.clean(text)
        if (spoken.isEmpty()) return
        interrupted = false
        val previous = VoiceState.mode
        VoiceState.mode = VoiceState.Mode.SPEAKING
        try {
            withContext(Dispatchers.IO) {
                if (settings.elevenLabsKey.isNotBlank() && tryVoice { elevenLabs.speak(settings.elevenLabsKey, settings.elevenLabsVoice, spoken) }) return@withContext
                if (interrupted) return@withContext
                val pack = neuralPack()
                if (pack != null && tryVoice { neural.speak(spoken, pack) }) return@withContext
                if (!interrupted) speakWithPhoneVoice(spoken)
            }
        } finally {
            VoiceState.level = 0f
            if (VoiceState.mode == VoiceState.Mode.SPEAKING) VoiceState.mode = previous
        }
    }

    private inline fun tryVoice(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: Exception) {
        Log.w("Blecky", "Voice failed, falling back", e)
        false
    }

    private suspend fun speakWithPhoneVoice(text: String) {
        if (!ttsReady.await()) return
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        done.await()
    }

    fun stopSpeaking() {
        interrupted = true
        elevenLabs.stop()
        neural.stop()
        tts.stop()
    }

    fun beep() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 150)
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 400)
        }
    }

    /** Listens for one phrase. Returns null on silence or if nothing was understood. */
    suspend fun listen(): String? = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return@withContext null
        val previous = VoiceState.mode
        VoiceState.mode = VoiceState.Mode.LISTENING
        try {
            hear()
        } finally {
            VoiceState.level = 0f
            if (VoiceState.mode == VoiceState.Mode.LISTENING) VoiceState.mode = previous
        }
    }

    private suspend fun hear(): String? =
        suspendCancellableCoroutine { cont ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            var finished = false
            fun finish(result: String?) {
                if (finished) return
                finished = true
                recognizer.destroy()
                cont.resume(result?.trim()?.takeIf { it.isNotEmpty() })
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) =
                    finish(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                override fun onError(error: Int) = finish(null)
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) {
                    VoiceState.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            cont.invokeOnCancellation { if (!finished) { finished = true; recognizer.destroy() } }
            recognizer.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, settings.speechLanguage)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L),
            )
        }
}

/**
 * Google's text-to-speech voices don't report gender, so rank known warm female voices first
 * (US, UK, Indian and Australian English).
 */
internal fun femaleRank(voiceName: String): Int {
    val preferred = listOf(
        "en-us-x-sfg", "en-us-x-tpf", "en-us-x-iob", "en-us-x-tpc", "en-us-x-iog",
        "en-gb-x-gba", "en-gb-x-gbc", "en-gb-x-gbg", "en-in-x-ena", "en-in-x-enc", "en-au-x-afa", "en-au-x-aua",
    )
    val index = preferred.indexOfFirst { voiceName.lowercase().startsWith(it) }
    return if (index >= 0) index else preferred.size
}

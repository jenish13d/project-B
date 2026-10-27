package com.blecky.assistant.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Records the owner saying the wake phrase a few times and averages the voiceprints.
 * The wake listener must be stopped first so the microphone is free.
 */
class VoiceEnrollment(
    private val context: Context,
    private val onPrompt: (String) -> Unit,
    private val onDone: (FloatArray?) -> Unit,
) : RecognitionListener {
    private val samples = mutableListOf<FloatArray>()
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null
    private var finished = false

    suspend fun start() {
        val rec = withContext(Dispatchers.IO) {
            Recognizer(ModelInstaller.speech(context), 16000f).apply {
                setSpeakerModel(ModelInstaller.speaker(context))
            }
        }
        recognizer = rec
        speech = SpeechService(rec, 16000f).also { it.startListening(this) }
        prompt()
    }

    fun cancel() = finish(null)

    private fun prompt() = onPrompt("Say “Hey Blecky” in your normal voice (${samples.size + 1} of $SAMPLES), then pause.")

    override fun onResult(hypothesis: String?) {
        val voiceprint = hypothesis?.let { runCatching { SpeakerProfile.fromResult(JSONObject(it), ENROLL_MIN_FRAMES) }.getOrNull() }
        if (voiceprint == null) {
            onPrompt("I didn't catch enough of that. Say “Hey Blecky” again (${samples.size + 1} of $SAMPLES).")
            return
        }
        samples += voiceprint
        if (samples.size >= SAMPLES) finish(SpeakerProfile.average(samples)) else prompt()
    }

    override fun onPartialResult(hypothesis: String?) = Unit
    override fun onFinalResult(hypothesis: String?) = Unit
    override fun onError(exception: Exception?) = finish(null)
    override fun onTimeout() = finish(null)

    private fun finish(result: FloatArray?) {
        if (finished) return
        finished = true
        speech?.let {
            it.stop()
            it.shutdown()
        }
        speech = null
        recognizer?.close()
        recognizer = null
        onDone(result)
    }

    companion object {
        const val SAMPLES = 5
        private const val ENROLL_MIN_FRAMES = 40
    }
}

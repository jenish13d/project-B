package com.blecky.assistant.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.vosk.Recognizer
import kotlin.concurrent.thread

/**
 * Feeds the microphone to the wake-word recognizer, but only while [VoiceGate] hears sound.
 * Replaces Vosk's SpeechService, which decoded every millisecond of silence all day and kept the
 * processor (and the phone) warm. Results arrive on the main thread.
 */
class GatedWakeListener(
    private val recognizer: Recognizer,
    private val onResult: (String) -> Unit,
    private val onError: (Exception) -> Unit,
) {
    @Volatile
    private var running = false
    private var worker: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission") // The service only starts once the microphone is allowed.
    fun start() {
        if (running) return
        running = true
        worker = thread(name = "blecky-wake") {
            val chunkSamples = SAMPLE_RATE / 10 // 100 ms
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), chunkSamples * 4),
                )
            } catch (e: Exception) {
                fail(e)
                return@thread
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                fail(IllegalStateException("Microphone is busy"))
                return@thread
            }
            val gate = VoiceGate()
            val buffer = ShortArray(chunkSamples)
            try {
                record.startRecording()
                while (running) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n <= 0) continue
                    for (chunk in gate.process(buffer.copyOf(n))) {
                        if (recognizer.acceptWaveForm(chunk, chunk.size)) {
                            val result = recognizer.result
                            main.post { if (running) onResult(result) }
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) fail(e)
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }
    }

    /** Stops listening and frees the microphone; returns once it's released. */
    fun stop() {
        running = false
        worker?.join(1_000)
        worker = null
    }

    private fun fail(e: Exception) {
        Log.w("Blecky", "Wake microphone failed", e)
        running = false
        main.post { onError(e) }
    }

    companion object {
        const val SAMPLE_RATE = 16000
    }
}

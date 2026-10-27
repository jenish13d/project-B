package com.blecky.assistant.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.sqrt

/** Streams raw audio to the speaker as it's generated, so Blecky starts talking straight away. */
class PcmPlayer(private val sampleRate: Int, private val floatSamples: Boolean) {
    private val bytesPerFrame = if (floatSamples) 4 else 2
    private val bufferBytes = maxOf(
        AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            if (floatSamples) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT,
        ),
        sampleRate * bytesPerFrame / 4,
    )
    private val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(if (floatSamples) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(bufferBytes)
        .build()

    private var framesWritten = 0L
    private var oddByte: Byte? = null

    @Volatile
    var stopped = false
        private set

    fun write(samples: FloatArray) {
        if (stopped || samples.isEmpty()) return
        ensurePlaying()
        VoiceState.level = rms(samples)
        track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        framesWritten += samples.size
    }

    /** 16-bit little-endian PCM, in chunks of any length. */
    fun write(bytes: ByteArray, length: Int) {
        if (stopped || length <= 0) return
        ensurePlaying()
        var data = bytes.copyOf(length)
        oddByte?.let { data = byteArrayOf(it) + data }
        oddByte = if (data.size % 2 == 1) data.last() else null
        val even = data.size - data.size % 2
        if (even == 0) return
        val shorts = ShortArray(even / 2) { i -> ((data[2 * i].toInt() and 0xFF) or (data[2 * i + 1].toInt() shl 8)).toShort() }
        VoiceState.level = rms(FloatArray(shorts.size) { shorts[it] / 32768f })
        track.write(shorts, 0, shorts.size, AudioTrack.WRITE_BLOCKING)
        framesWritten += shorts.size
    }

    /** Waits for everything written so far to be heard, then frees the speaker. */
    fun finish() {
        try {
            if (!stopped && framesWritten > 0) {
                // Pad with silence so short clips aren't held back waiting for a full buffer.
                val padFrames = bufferBytes / bytesPerFrame
                if (floatSamples) track.write(FloatArray(padFrames), 0, padFrames, AudioTrack.WRITE_BLOCKING)
                else track.write(ShortArray(padFrames), 0, padFrames, AudioTrack.WRITE_BLOCKING)
                val deadline = System.currentTimeMillis() + framesWritten * 1000 / sampleRate + 3_000
                while (!stopped && track.playbackHeadPosition.toLong() < framesWritten && System.currentTimeMillis() < deadline) {
                    Thread.sleep(25)
                }
            }
        } finally {
            release()
        }
    }

    fun stop() {
        stopped = true
        runCatching {
            track.pause()
            track.flush()
        }
    }

    private fun ensurePlaying() {
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
    }

    private fun release() {
        VoiceState.level = 0f
        runCatching { track.stop() }
        track.release()
    }

    private fun rms(samples: FloatArray): Float {
        var sum = 0.0
        for (s in samples) sum += s * s
        return (sqrt(sum / samples.size) * 4).toFloat().coerceIn(0f, 1f)
    }
}

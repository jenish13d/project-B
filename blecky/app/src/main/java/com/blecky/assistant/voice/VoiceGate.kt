package com.blecky.assistant.voice

import kotlin.math.sqrt

/**
 * Decides which microphone chunks are worth sending to the wake-word recognizer. In silence the
 * recognizer gets nothing, so the processor can rest; when someone speaks, it gets a short
 * pre-roll (so the start of "hey" isn't lost), the speech, and a little trailing quiet so it can
 * tell the phrase has ended. Pure logic, so it can be tested without a microphone.
 */
class VoiceGate(
    private val preRollChunks: Int = 3,
    private val hangoverChunks: Int = 10,
    private val minLevel: Double = 250.0,
) {
    private val preRoll = ArrayDeque<ShortArray>()
    private var noiseFloor = minLevel / 3
    private var quietChunksLeft = 0

    val isOpen get() = quietChunksLeft > 0

    /** Chunks to feed the recognizer now (possibly none) for one chunk of 16-bit audio. */
    fun process(chunk: ShortArray): List<ShortArray> {
        val level = rms(chunk)
        val loud = level > maxOf(minLevel, noiseFloor * 3)
        if (!loud) noiseFloor = noiseFloor * 0.95 + level * 0.05

        if (loud) {
            val out = if (isOpen) listOf(chunk) else preRoll.toList() + listOf(chunk)
            preRoll.clear()
            quietChunksLeft = hangoverChunks
            return out
        }
        if (isOpen) {
            quietChunksLeft--
            return listOf(chunk)
        }
        preRoll.addLast(chunk)
        while (preRoll.size > preRollChunks) preRoll.removeFirst()
        return emptyList()
    }

    companion object {
        fun rms(chunk: ShortArray): Double {
            if (chunk.isEmpty()) return 0.0
            var sum = 0.0
            for (s in chunk) sum += s.toDouble() * s
            return sqrt(sum / chunk.size)
        }
    }
}

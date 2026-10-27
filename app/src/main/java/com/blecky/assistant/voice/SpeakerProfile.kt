package com.blecky.assistant.voice

import org.json.JSONObject
import kotlin.math.sqrt

/** Voiceprints (x-vectors from the Vosk speaker model) and how closely two of them match. */
object SpeakerProfile {
    /** Results shorter than this many frames don't carry a reliable voiceprint. */
    const val MIN_FRAMES = 20

    fun fromResult(result: JSONObject, minFrames: Int = MIN_FRAMES): FloatArray? {
        val spk = result.optJSONArray("spk") ?: return null
        if (result.optInt("spk_frames", 0) < minFrames) return null
        return FloatArray(spk.length()) { spk.getDouble(it).toFloat() }
    }

    fun normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.fold(0.0) { acc, x -> acc + x * x }).toFloat()
        return if (norm == 0f) v.copyOf() else FloatArray(v.size) { v[it] / norm }
    }

    fun average(samples: List<FloatArray>): FloatArray {
        require(samples.isNotEmpty())
        val sum = FloatArray(samples.first().size)
        samples.map(::normalize).forEach { s -> s.forEachIndexed { i, x -> sum[i] += x } }
        return normalize(sum)
    }

    /** Cosine similarity: 1 for the same direction, around 0 for unrelated voices. */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        val na = normalize(a)
        val nb = normalize(b)
        return na.indices.fold(0f) { acc, i -> acc + na[i] * nb[i] }
    }

    fun encode(v: FloatArray): String = v.joinToString(",")

    fun decode(text: String): FloatArray? =
        text.takeIf { it.isNotBlank() }?.split(',')?.mapNotNull { it.toFloatOrNull() }?.toFloatArray()?.takeIf { it.isNotEmpty() }
}

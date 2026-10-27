package com.blecky.assistant

import com.blecky.assistant.voice.WakeWord
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {
    private fun result(vararg words: Pair<String, Double>) = JSONObject()
        .put("text", words.joinToString(" ") { it.first })
        .put("result", JSONArray(words.map { JSONObject().put("word", it.first).put("conf", it.second) }))

    private fun wakes(vararg words: Pair<String, Double>, alone: Boolean = false) =
        WakeWord.matches(WakeWord.words(result(*words)), alone)

    @Test
    fun `wakes on hey blecky and hey bleck when the name is heard clearly`() {
        assertTrue(wakes("hey" to 1.0, "blackie" to 1.0))
        assertTrue(wakes("hey" to 1.0, "black" to 0.53))
        assertTrue(wakes("[unk]" to 1.0, "okay" to 1.0, "blackie" to 0.9))
        assertFalse(wakes("hey" to 1.0, "black" to 0.3))
    }

    @Test
    fun `decoys and noise never wake her`() {
        // Measured: "Hey, buddy" is heard as "hey becky" with full confidence.
        assertFalse(wakes("hey" to 1.0, "becky" to 1.0))
        assertFalse(wakes("[unk]" to 1.0))
        assertFalse(wakes("okay" to 1.0, "[unk]" to 1.0))
        // Measured: "Blessed" and "Back to work" come out as "bleck [unk]".
        assertFalse(wakes("bleck" to 0.76, "[unk]" to 1.0, alone = true))
    }

    @Test
    fun `bleck on its own only when switched on and confident`() {
        assertFalse(wakes("bleck" to 0.9))
        assertTrue(wakes("bleck" to 0.9, alone = true))
        assertTrue(wakes("[unk]" to 1.0, "bleck" to 0.8, alone = true))
        assertFalse(wakes("black" to 0.52, alone = true))
    }

    @Test
    fun `greets by name when known`() {
        assertTrue((1..40).any { WakeWord.greeting("Jai").contains("Jai") })
        repeat(40) {
            val g = WakeWord.greeting("", hour = it % 24)
            assertFalse(g, g.contains("%s") || g.contains(" ,") || g.contains(", ?") || g.contains(", ."))
        }
    }

    @Test
    fun `greetings do not repeat back to back`() {
        val said = (1..30).map { WakeWord.greeting("Jai", hour = 14) }
        assertTrue(said.zipWithNext().none { (a, b) -> a == b })
    }

    @Test
    fun `understands yes and no`() {
        assertTrue(WakeWord.isYes("yes"))
        assertTrue(WakeWord.isYes("Yeah send it"))
        assertTrue(WakeWord.isYes("go ahead"))
        assertFalse(WakeWord.isYes("no"))
        assertFalse(WakeWord.isYes("no don't send it"))
        assertFalse(WakeWord.isYes("wait"))
        assertFalse(WakeWord.isYes(null))
    }

    @Test
    fun `recognises short sign-offs in English, Hindi and Gujarati`() {
        listOf("Thank you", "bye!", "thanks Becky", "ok bye", "thank you so much", "धन्यवाद", "बस ठीक है", "આભાર").forEach {
            assertTrue(it, WakeWord.isGoodbye(it))
        }
        listOf("stop the timer", "remind me later", "बस स्टॉप कहाँ है", "thanks, now call mom and tell her I'm late", "what's the weather").forEach {
            assertFalse(it, WakeWord.isGoodbye(it))
        }
    }
}

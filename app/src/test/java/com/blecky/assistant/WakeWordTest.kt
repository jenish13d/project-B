package com.blecky.assistant

import com.blecky.assistant.voice.WakeWord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {
    @Test
    fun `wakes on hey blecky sound-alikes`() {
        assertTrue(WakeWord.matches("hey blackie"))
        assertTrue(WakeWord.matches("[unk] hey becky"))
        assertTrue(WakeWord.matches("Okay Blackie!"))
        assertTrue(WakeWord.matches("hey bleck", isFinal = false))
    }

    @Test
    fun `wakes on bleck said on its own, but only once the phrase is finished`() {
        assertTrue(WakeWord.matches("bleck"))
        assertTrue(WakeWord.matches("black"))
        assertTrue(WakeWord.matches("[unk] bleck"))
        assertFalse(WakeWord.matches("bleck", isFinal = false))
        assertFalse(WakeWord.matches("black [unk]"))
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
    fun `ignores other speech`() {
        assertFalse(WakeWord.matches(""))
        assertFalse(WakeWord.matches(null))
        assertFalse(WakeWord.matches("hey blackberry"))
        assertFalse(WakeWord.matches("[unk]"))
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
    fun `recognises goodbyes only on their own`() {
        assertTrue(WakeWord.isGoodbye("Thank you"))
        assertTrue(WakeWord.isGoodbye("bye!"))
        assertFalse(WakeWord.isGoodbye("stop the timer"))
    }
}

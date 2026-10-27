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
    }

    @Test
    fun `ignores other speech`() {
        assertFalse(WakeWord.matches(""))
        assertFalse(WakeWord.matches(null))
        assertFalse(WakeWord.matches("blackie"))
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

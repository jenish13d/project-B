package com.blecky.assistant

import com.blecky.assistant.tools.Memory
import com.blecky.assistant.tools.Reminders
import com.blecky.assistant.voice.SpeakerProfile
import com.blecky.assistant.voice.femaleRank
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class PersonalTest {
    @Test
    fun `voiceprints match themselves and not opposite voices`() {
        val owner = floatArrayOf(1f, 2f, 3f, 4f)
        assertEquals(1f, SpeakerProfile.similarity(owner, floatArrayOf(2f, 4f, 6f, 8f)), 1e-5f)
        assertEquals(-1f, SpeakerProfile.similarity(owner, floatArrayOf(-1f, -2f, -3f, -4f)), 1e-5f)
        assertEquals(0f, SpeakerProfile.similarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-5f)
        assertEquals(0f, SpeakerProfile.similarity(owner, floatArrayOf(1f)), 0f)
    }

    @Test
    fun `averaged voiceprint sits between the samples`() {
        val avg = SpeakerProfile.average(listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 10f)))
        assertEquals(avg[0], avg[1], 1e-5f)
        assertEquals(1f, avg[0] * avg[0] + avg[1] * avg[1], 1e-5f)
    }

    @Test
    fun `voiceprints round-trip and need enough audio`() {
        val v = floatArrayOf(0.25f, -1.5f, 3f)
        assertArrayEquals(v, SpeakerProfile.decode(SpeakerProfile.encode(v)), 0f)
        assertNull(SpeakerProfile.decode(""))
        val result = JSONObject().put("spk", JSONArray(listOf(0.1, 0.2))).put("spk_frames", 10)
        assertNull(SpeakerProfile.fromResult(result))
        result.put("spk_frames", 80)
        assertEquals(2, SpeakerProfile.fromResult(result)?.size)
        assertNull(SpeakerProfile.fromResult(JSONObject().put("text", "hey bleck")))
    }

    @Test
    fun `memory prompt lists facts and asks for a missing name`() {
        val empty = Memory.prompt("", emptyList())
        assertTrue(empty.contains("set_user_name"))
        val full = Memory.prompt("Jai", listOf(1 to "Loves cricket", 4 to "Sister is Priya"))
        assertTrue(full.contains("Jai"))
        assertTrue(full.contains("#4: Sister is Priya"))
    }

    @Test
    fun `daily check-in is today if still ahead, else tomorrow`() {
        val morning = LocalDateTime.of(2026, 10, 27, 7, 0)
        assertEquals(LocalDateTime.of(2026, 10, 27, 9, 0), Reminders.nextDaily(9 * 60, morning))
        assertEquals(LocalDateTime.of(2026, 10, 28, 9, 0), Reminders.nextDaily(9 * 60, morning.withHour(9)))
    }

    @Test
    fun `female voices are preferred`() {
        assertTrue(femaleRank("en-us-x-sfg-local") < femaleRank("en-us-x-iom-local"))
        assertTrue(femaleRank("en-in-x-ena-network") < femaleRank("en-in-x-end-network"))
    }
}

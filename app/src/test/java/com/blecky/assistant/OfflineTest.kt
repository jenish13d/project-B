package com.blecky.assistant

import com.blecky.assistant.brain.LocalCommands
import com.blecky.assistant.brain.LocalCommands.Command
import com.blecky.assistant.brain.OfflineEngine
import com.blecky.assistant.brain.OfflineModels
import com.blecky.assistant.brain.Persona
import com.blecky.assistant.tools.InnerLife
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineTest {
    private fun call(name: String, sim: String? = null) = Command("call", mapOf("contact" to name, "sim" to sim))

    @Test
    fun `calls in English, Hinglish, Hindi and Gujarati`() {
        assertEquals(call("jash"), LocalCommands.parse("Call Jash"))
        assertEquals(call("jayesh", "jio"), LocalCommands.parse("call Jayesh on Jio"))
        assertEquals(call("mom", "sim 2"), LocalCommands.parse("Hey Blecky, call mom from SIM 2."))
        assertEquals(call("papa"), LocalCommands.parse("papa ko call karo"))
        assertEquals(call("jash", "jio"), LocalCommands.parse("Jash ko call karo jio se"))
        assertEquals(Command("call", mapOf("contact" to "मम्मी")), LocalCommands.parse("मम्मी को कॉल करो"))
        assertEquals(Command("call", mapOf("contact" to "પપ્પા")), LocalCommands.parse("પપ્પાને કૉલ કર"))
    }

    @Test
    fun `timers, torch, apps, time and battery`() {
        assertEquals(Command("set_timer", mapOf("seconds" to 600)), LocalCommands.parse("set a timer for 10 minutes"))
        assertEquals(Command("set_timer", mapOf("seconds" to 300)), LocalCommands.parse("5 minute ka timer lagao"))
        assertEquals(Command("set_timer", mapOf("seconds" to 30)), LocalCommands.parse("timer 30 seconds"))
        assertEquals(Command("flashlight", mapOf("on" to true)), LocalCommands.parse("turn on the flashlight"))
        assertEquals(Command("flashlight", mapOf("on" to false)), LocalCommands.parse("torch off"))
        assertEquals(Command("flashlight", mapOf("on" to true)), LocalCommands.parse("torch jalao"))
        assertEquals(Command("flashlight", mapOf("on" to false)), LocalCommands.parse("टॉर्च बंद करो"))
        assertEquals(Command("open_app", mapOf("app_name" to "whatsapp")), LocalCommands.parse("Open WhatsApp"))
        assertEquals(Command("open_app", mapOf("app_name" to "youtube")), LocalCommands.parse("youtube kholo"))
        assertEquals(Command("open_app", mapOf("app_name" to "કેમેરા")), LocalCommands.parse("કેમેરા ખોલ"))
        assertEquals(Command("time"), LocalCommands.parse("What time is it?"))
        assertEquals(Command("time"), LocalCommands.parse("kitne baje hai"))
        assertEquals(Command("device_status"), LocalCommands.parse("how's my battery"))
    }

    @Test
    fun `ordinary conversation is left for the AI`() {
        listOf("I'm stressed about exams", "tell me a joke", "what should I eat tonight", "call me crazy but I love rain").forEach {
            val parsed = LocalCommands.parse(it)
            assertNull("$it -> $parsed", parsed)
        }
        assertNull(LocalCommands.parse("मुझे एक जोक सुनाओ"))
    }

    @Test
    fun `inner life carries moods, fades them and recalls moments`() {
        val now = 1_800_000_000_000L
        val hour = 3_600_000L
        val state = InnerLife.State("proud of them", now - 2 * hour, "stressed about exams", now - 20 * hour, listOf(now - 30 * hour to "They passed the driving test"))
        val prompt = InnerLife.prompt(state, now, hour = 14)
        assertTrue(prompt.contains("proud of them"))
        assertTrue(prompt.contains("stressed about exams"))
        assertTrue(prompt.contains("yesterday: They passed the driving test"))
        assertFalse(InnerLife.currentMood(state.copy(moodSince = now - 13 * hour), now, hour = 14).contains("proud"))
        assertTrue(InnerLife.currentMood(state, now, hour = 1).contains("sleepy"))
    }

    @Test
    fun `models fit the phone and tool names avoid gemma keywords`() {
        assertEquals("gemma4-e2b", OfflineModels.recommended(6.0).id)
        assertEquals("gemma4-e2b", OfflineModels.recommended(3.7).id)
        assertEquals("qwen3-1.7b", OfflineModels.recommended(3.0).id)
        assertEquals("make_call", OfflineEngine.offlineName("call"))
        assertEquals("set_timer", OfflineEngine.offlineName("set_timer"))
        assertEquals("call Jash", Persona.unstamp(Persona.userMessage("call Jash", fromScheduler = false)))
    }
}

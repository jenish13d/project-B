package com.blecky.assistant

import com.blecky.assistant.tools.Args
import com.blecky.assistant.tools.NotesStore
import com.blecky.assistant.tools.Reminders
import com.blecky.assistant.tools.ToolError
import com.blecky.assistant.tools.allTools
import com.blecky.assistant.tools.looksLikeNumber
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

class ToolsTest {
    private val now = LocalDateTime.of(2026, 10, 27, 16, 0)

    @Test
    fun `reminder time from minutes or ISO`() {
        assertEquals(now.plusMinutes(20), Reminders.resolveTime(null, 20, now))
        assertEquals(LocalDateTime.of(2026, 10, 27, 18, 30), Reminders.resolveTime("2026-10-27T18:30", null, now))
        assertEquals(LocalDateTime.of(2026, 10, 28, 9, 0), Reminders.resolveTime("2026-10-28 09:00:00", null, now))
    }

    @Test(expected = ToolError::class)
    fun `reminder in the past is rejected`() {
        Reminders.resolveTime("2026-10-27T10:00", null, now)
    }

    @Test(expected = ToolError::class)
    fun `reminder needs a time`() {
        Reminders.resolveTime(null, null, now)
    }

    @Test
    fun `args coerce model input`() {
        val args = Args(mapOf("n" to 5.0, "s" to " hi ", "b" to "true", "blank" to "  ", "ns" to "42"))
        assertEquals(5, args.int("n"))
        assertEquals(42, args.int("ns"))
        assertEquals("hi", args.string("s"))
        assertEquals(true, args.optBool("b"))
        assertNull(args.optString("blank"))
    }

    @Test
    fun `notes store adds lists and removes`() {
        val file = File.createTempFile("notes", ".json").apply { delete() }
        val store = NotesStore(file)
        assertEquals(1, store.add(JSONObject().put("text", "milk")))
        assertEquals(2, store.add(JSONObject().put("text", "eggs")))
        assertEquals(listOf("milk", "eggs"), store.load().map { it.getString("text") })
        assertEquals("milk", store.remove(1)?.getString("text"))
        assertNull(store.remove(1))
        assertEquals(3, store.add(JSONObject().put("text", "bread")))
        file.delete()
    }

    @Test
    fun `phone numbers vs names`() {
        assertTrue(looksLikeNumber("+1 (555) 010-0100"))
        assertFalse(looksLikeNumber("Mom"))
    }

    @Test
    fun `tool names are unique and valid`() {
        val names = allTools().map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { Regex("^[a-z_]{1,64}$").matches(it) })
    }
}

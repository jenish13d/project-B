package com.blecky.assistant

import com.blecky.assistant.updates.Diagnostics
import com.blecky.assistant.updates.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class UpdatesTest {
    // The shape the GitHub workflow writes.
    private val json = """{"versionCode": 1042, "versionName": "8.4.42", "notes": "Fix crash when checking for internet",
        "apkUrl": "https://github.com/jenish13d/project-B/releases/download/blecky-latest/blecky.apk", "sha256": "ABC123"}"""

    @Test
    fun `reads the published build and compares versions`() {
        val update = Updater.parse(json)
        assertEquals(1042, update.versionCode)
        assertEquals("abc123", update.sha256)
        assertTrue(Updater.isNewer(update, installedVersionCode = 12))
        assertFalse(Updater.isNewer(update, installedVersionCode = 1042))
        assertTrue(Updater.INFO_URL.startsWith("https://github.com/jenish13d/project-B/releases/download/blecky-latest/"))
    }

    @Test
    fun `report links open a prefilled issue and stay short enough`() {
        val body = Diagnostics.format(linkedMapOf("Blecky" to "8.4", "Battery" to "61%, temperature 41.2°C"), "java.lang.SecurityException: boom", "E Blecky: oops\n".repeat(2_000))
        val url = Diagnostics.issueUrl("[Blecky] report", body)
        assertTrue(url.startsWith("https://github.com/jenish13d/project-B/issues/new?title=%5BBlecky%5D+report&body="))
        assertTrue(url.length <= 7_500)
        val decoded = URLDecoder.decode(url.substringAfter("&body="), "UTF-8")
        assertTrue(decoded.contains("temperature 41.2°C"))
        assertTrue(decoded.contains("SecurityException"))
        assertTrue(decoded.endsWith("(cut to fit)"))
        assertEquals(1, Regex("cut to fit").findAll(decoded).count())
    }

    @Test
    fun `public status carries app state only`() {
        val public = Diagnostics.format(linkedMapOf("Listening" to "service running"), crash = null, logs = "")
        assertTrue(public.contains("service running"))
        assertFalse(public.contains("warnings"))
        assertFalse(public.contains("crash", ignoreCase = true))
    }
}

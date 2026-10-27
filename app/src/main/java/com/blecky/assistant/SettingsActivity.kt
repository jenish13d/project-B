package com.blecky.assistant

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.blecky.assistant.tools.Memory
import com.blecky.assistant.tools.NotificationReader
import com.blecky.assistant.tools.Reminders
import com.blecky.assistant.voice.ModelInstaller
import com.blecky.assistant.voice.SpeakerProfile
import com.blecky.assistant.voice.VoiceEnrollment
import com.blecky.assistant.voice.WakeWordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : Activity() {
    private val scope = MainScope()
    private lateinit var status: TextView
    private var busy = false
    private var enrollment: VoiceEnrollment? = null

    private fun button(id: Int): Button = findViewById(id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        status = findViewById(R.id.status)
        val settings = blecky.settings

        val keyField = findViewById<EditText>(R.id.api_key)
        button(R.id.save_key).setOnClickListener {
            if (keyField.text.isNotBlank()) {
                settings.apiKey = keyField.text.toString()
                keyField.text.clear()
                blecky.brain.reset()
                toast("API key saved")
                refresh()
            }
        }
        val elevenField = findViewById<EditText>(R.id.eleven_key)
        button(R.id.save_eleven).setOnClickListener {
            settings.elevenLabsKey = elevenField.text.toString()
            elevenField.text.clear()
            toast(if (settings.elevenLabsKey.isBlank()) "ElevenLabs voice turned off" else "ElevenLabs voice on")
            refresh()
        }

        button(R.id.toggle_listening).setOnClickListener {
            if (WakeWordService.isRunning && settings.listening) WakeWordService.stop(this) else startListening()
            status.postDelayed(::refresh, 500)
        }
        button(R.id.follow_up).setOnClickListener {
            val next = FOLLOW_UP.firstOrNull { it.first > settings.followUpSeconds } ?: FOLLOW_UP.first()
            settings.followUpSeconds = next.first
            refresh()
        }

        button(R.id.natural_voice).setOnClickListener { installNaturalVoice() }
        button(R.id.change_voice).setOnClickListener {
            scope.launch {
                val name = blecky.voice.nextVoice()
                if (name == null) {
                    toast("No English voices found. Install Google's speech engine from the Play Store.")
                } else {
                    refresh()
                    blecky.voice.speak("Hi! I'm Blecky. How do I sound?")
                }
            }
        }

        button(R.id.grant_permissions).setOnClickListener { requestPermissions(Permissions.RUNTIME, 1) }
        button(R.id.grant_overlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button(R.id.grant_battery).setOnClickListener {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        button(R.id.grant_notifications).setOnClickListener {
            if (Permissions.notificationAccess(this)) openNotificationAccess() else explainNotificationAccess()
        }

        button(R.id.enroll_voice).setOnClickListener { if (enrollment != null) enrollment?.cancel() else enrollVoice() }
        button(R.id.voice_lock).setOnClickListener {
            if (SpeakerProfile.decode(settings.voiceProfile) == null) {
                enrollVoice()
            } else {
                settings.voiceLock = !settings.voiceLock
                restartListening()
                refresh()
            }
        }
        button(R.id.voice_strictness).setOnClickListener {
            val levels = STRICTNESS.map { it.second }
            settings.voiceThreshold = levels.firstOrNull { it > settings.voiceThreshold + 0.001f } ?: levels.first()
            restartListening()
            refresh()
        }
        button(R.id.check_in_time).setOnClickListener { pickCheckInTime() }
        button(R.id.memories).setOnClickListener { showMemories() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        enrollment?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun startListening() {
        when {
            blecky.settings.apiKey.isBlank() -> toast("Paste your Anthropic API key first")
            !Permissions.hasMic(this) -> requestPermissions(Permissions.RUNTIME, 1)
            !ModelInstaller.isInstalled(this) -> installModels { WakeWordService.listen(this) }
            else -> WakeWordService.listen(this)
        }
    }

    private fun restartListening() {
        if (!WakeWordService.isRunning || !blecky.settings.listening) return
        WakeWordService.stop(this)
        WakeWordService.listen(this)
    }

    private fun installModels(then: () -> Unit) = runDownload(then) {
        ModelInstaller.install(this) { text -> runOnUiThread { status.text = text } }
    }

    private fun installNaturalVoice() {
        if (blecky.voice.kokoro.isInstalled()) {
            toast("The natural voice is already installed")
            return
        }
        runDownload({
            toast("Natural voice ready")
            scope.launch { blecky.voice.speak("Hey! This is my new voice. Much better, right?") }
        }) {
            blecky.voice.kokoro.install { text -> runOnUiThread { status.text = text } }
        }
    }

    private fun runDownload(then: () -> Unit, download: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                download()
            } catch (e: Exception) {
                status.text = "Download failed: ${e.message}. Check your internet and try again."
                return@launch
            } finally {
                busy = false
            }
            then()
            refresh()
        }
    }

    /** Records the owner's voice a few times, then turns on the voice lock. */
    private fun enrollVoice() {
        if (!Permissions.hasMic(this)) return requestPermissions(Permissions.RUNTIME, 1)
        if (!ModelInstaller.isInstalled(this)) return installModels(::enrollVoice)
        val wasListening = WakeWordService.isRunning && blecky.settings.listening
        if (wasListening) WakeWordService.stop(this)
        busy = true
        button(R.id.enroll_voice).text = "Cancel voice setup"
        scope.launch {
            delay(700) // let the listener release the microphone
            val session = VoiceEnrollment(
                this@SettingsActivity,
                onPrompt = { status.text = it },
                onDone = { voiceprint ->
                    enrollment = null
                    busy = false
                    button(R.id.enroll_voice).text = "Teach Blecky my voice"
                    if (voiceprint != null) {
                        blecky.settings.voiceProfile = SpeakerProfile.encode(voiceprint)
                        blecky.settings.voiceLock = true
                        toast("Got it. Blecky now only wakes for your voice.")
                    } else {
                        toast("Voice setup stopped")
                    }
                    if (wasListening) WakeWordService.listen(this@SettingsActivity)
                    refresh()
                },
            )
            enrollment = session
            try {
                session.start()
            } catch (e: Exception) {
                session.cancel()
                toast("Couldn't start voice setup: ${e.message}")
            }
        }
    }

    private fun pickCheckInTime() {
        val current = blecky.settings.checkInMinute.takeIf { it >= 0 } ?: (9 * 60)
        val apply = { minute: Int ->
            blecky.settings.checkInMinute = minute
            Reminders.scheduleDaily(this)
            refresh()
        }
        TimePickerDialog(this, { _, hour, minute -> apply(hour * 60 + minute) }, current / 60, current % 60, true).apply {
            setButton(TimePickerDialog.BUTTON_NEUTRAL, "Turn off") { _, _ -> apply(-1) }
            show()
        }
    }

    /** Opens Blecky's own notification-access switch, falling back to the full list. */
    private fun openNotificationAccess() {
        val component = ComponentName(this, NotificationReader::class.java).flattenToString()
        val direct = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
        runCatching { startActivity(direct) }
            .onFailure { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }

    /**
     * Android 13+ blocks notification access for apps installed from an APK file ("restricted
     * setting") until the user allows it from the app's info page.
     */
    private fun explainNotificationAccess() {
        AlertDialog.Builder(this)
            .setTitle("Let Blecky read emails and messages")
            .setMessage(
                "If Android says this setting is restricted or not allowed, that's because Blecky was " +
                    "installed from a file instead of the Play Store. To allow it:\n\n" +
                    "1. Tap “Try to turn it on” and switch Blecky on. If Android blocks it, tap OK.\n" +
                    "2. Come back and tap “Open app info”.\n" +
                    "3. Tap the ⋮ menu (top right), then “Allow restricted settings”, and confirm with your PIN or fingerprint.\n" +
                    "4. Tap “Try to turn it on” again and switch Blecky on.\n\n" +
                    "No ⋮ menu? Do step 1 first. The option only appears after Android has blocked it once.",
            )
            .setPositiveButton("Try to turn it on") { _, _ -> openNotificationAccess() }
            .setNeutralButton("Open app info") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showMemories() {
        scope.launch {
            val text = withContext(Dispatchers.IO) { Memory.promptFor(this@SettingsActivity).trim() }
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("What Blecky remembers")
                .setMessage(text)
                .setPositiveButton("OK", null)
                .setNeutralButton("Forget everything") { _, _ ->
                    AlertDialog.Builder(this@SettingsActivity)
                        .setMessage("Delete everything Blecky remembers about you, including your name?")
                        .setPositiveButton("Delete") { _, _ ->
                            Memory.store(this@SettingsActivity).save(emptyList())
                            blecky.settings.userName = ""
                            blecky.brain.reset()
                            toast("Blecky's memory is cleared")
                        }
                        .setNegativeButton("Keep", null)
                        .show()
                }
                .show()
        }
    }

    private fun refresh() {
        if (busy) return
        val settings = blecky.settings
        val missing = buildList {
            if (settings.apiKey.isBlank()) add("API key")
            if (!Permissions.hasMic(this@SettingsActivity)) add("step 1")
            if (!Settings.canDrawOverlays(this@SettingsActivity)) add("step 2")
            if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) add("step 3")
            if (!Permissions.notificationAccess(this@SettingsActivity)) add("step 4")
            if (!blecky.voice.kokoro.isInstalled() && settings.elevenLabsKey.isBlank()) add("natural voice")
        }
        val listening = WakeWordService.isRunning && settings.listening
        status.text = buildString {
            append(if (listening) "Listening. Say “Hey Blecky” or “Bleck”, even with the screen off." else "Not listening right now.")
            append("\nAPI key: ${if (settings.apiKey.isBlank()) "not set" else "saved (…${settings.apiKey.takeLast(4)})"}")
            if (missing.isNotEmpty()) append("\nStill to set up: ${missing.joinToString()}.")
        }
        button(R.id.toggle_listening).text = if (listening) "Stop listening" else "Start listening for “Hey Blecky”"
        button(R.id.follow_up).text = "Keep the conversation going: " +
            (FOLLOW_UP.firstOrNull { it.first == settings.followUpSeconds }?.second ?: "${settings.followUpSeconds} seconds")
        button(R.id.natural_voice).text = if (blecky.voice.kokoro.isInstalled()) {
            "Natural human voice: installed"
        } else {
            "Download natural human voice (100 MB)"
        }
        button(R.id.change_voice).text = "Try another voice (now: ${blecky.voice.currentVoiceName()})"

        val enrolled = SpeakerProfile.decode(settings.voiceProfile) != null
        button(R.id.voice_lock).text = when {
            !enrolled -> "Only wake for my voice: teach Blecky your voice first"
            settings.voiceLock -> "Only wake for my voice: on"
            else -> "Only wake for my voice: off"
        }
        val level = STRICTNESS.minByOrNull { kotlin.math.abs(it.second - settings.voiceThreshold) }?.first ?: "normal"
        val last = settings.lastVoiceScore.takeIf { it >= 0 }?.let { " (last try matched ${(it * 100).toInt()}%)" } ?: ""
        button(R.id.voice_strictness).text = "Voice match: $level$last"
        button(R.id.check_in_time).text = settings.checkInMinute.let {
            if (it < 0) "Daily check-in: off" else "Daily check-in: %02d:%02d".format(it / 60, it % 60)
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private val STRICTNESS = listOf("relaxed" to 0.35f, "normal" to 0.45f, "strict" to 0.55f)
        private val FOLLOW_UP = listOf(20 to "20 seconds", 45 to "45 seconds", 120 to "2 minutes", 600 to "until I say bye")
    }
}

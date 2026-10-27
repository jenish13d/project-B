package com.blecky.assistant

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import com.blecky.assistant.Settings as Prefs
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
    private var keyProvider = Prefs.PROVIDER_GEMINI
    private var enrollment: VoiceEnrollment? = null

    private fun button(id: Int): Button = findViewById(id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        status = findViewById(R.id.status)
        val settings = blecky.settings

        val keyField = findViewById<EditText>(R.id.api_key)
        button(R.id.provider).setOnClickListener {
            val order = BRAINS.map { it.first }
            settings.provider = order[(order.indexOf(settings.provider) + 1) % order.size]
            if (settings.provider != Prefs.PROVIDER_AUTO) keyProvider = settings.provider
            blecky.brain.reset()
            refresh()
        }
        button(R.id.key_for).setOnClickListener {
            val order = KEYS.map { it.first }
            keyProvider = order[(order.indexOf(keyProvider) + 1) % order.size]
            refresh()
        }
        button(R.id.save_key).setOnClickListener {
            if (keyField.text.isNotBlank()) {
                settings.setKeyFor(keyProvider, keyField.text.toString())
                keyField.text.clear()
                blecky.brain.reset()
                toast("Key saved")
                refresh()
            }
        }
        button(R.id.speech_mode).setOnClickListener {
            val order = SPEECH.map { it.first }
            settings.speechMode = order[(order.indexOf(settings.speechMode) + 1) % order.size]
            refresh()
        }
        button(R.id.name_alone).setOnClickListener {
            settings.wakeOnNameAlone = !settings.wakeOnNameAlone
            refresh()
        }
        button(R.id.gujarati_voice).setOnClickListener { setUpGujaratiVoice() }
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

        button(R.id.natural_voice).setOnClickListener { chooseNaturalVoice() }
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
        if (isXiaomi()) {
            findViewById<android.view.View>(R.id.xiaomi).visibility = android.view.View.VISIBLE
            button(R.id.xiaomi_autostart).setOnClickListener {
                openFirst(
                    Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                )
            }
            button(R.id.xiaomi_popups).setOnClickListener {
                openFirst(
                    Intent("miui.intent.action.APP_PERM_EDITOR")
                        .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                        .putExtra("extra_pkgname", packageName),
                    Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", packageName),
                )
            }
        }
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
            blecky.settings.activeKey.isBlank() -> toast("Paste your free AI key first (Brain section)")
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

    /** Lists the free offline voices; picking one downloads it if needed and switches to it. */
    private fun chooseNaturalVoice() {
        val voices = blecky.voice.neural
        val packs = com.blecky.assistant.voice.NeuralVoices.PACKS
        val labels = packs.map { pack ->
            val state = if (voices.isInstalled(pack)) "downloaded" else "${pack.sizeMb} MB"
            "${pack.label} · $state"
        }.toTypedArray()
        val selected = packs.indexOfFirst { it.id == blecky.settings.voicePack }
        AlertDialog.Builder(this)
            .setTitle("Choose Blecky's voice")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                dialog.dismiss()
                val pack = packs[which]
                val sayHello: () -> Unit = {
                    blecky.settings.voicePack = pack.id
                    refresh()
                    scope.launch { blecky.voice.speak("Hey! How do I sound? I can switch any time you like.") }
                }
                if (voices.isInstalled(pack)) sayHello() else runDownload(sayHello) {
                    voices.install(pack) { text -> runOnUiThread { status.text = text } }
                }
            }
            .setNegativeButton("Close", null)
            .show()
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
            if (settings.activeKey.isBlank()) add("AI key")
            if (!Permissions.hasMic(this@SettingsActivity)) add("step 1")
            if (!Settings.canDrawOverlays(this@SettingsActivity)) add("step 2")
            if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) add("step 3")
            if (!Permissions.notificationAccess(this@SettingsActivity)) add("step 4")
            if (blecky.voice.neuralPack() == null && settings.elevenLabsKey.isBlank()) add("natural voice")
        }
        val listening = WakeWordService.isRunning && settings.listening
        status.text = buildString {
            append("Blecky v${BuildConfig.VERSION_NAME}\n")
            append(if (listening) "Listening. Say “Hey Blecky” or “Bleck”, even with the screen off." else "Not listening right now.")
            append("\nBrain: ${if (settings.activeKey.isBlank()) "needs a free key (see Brain below)" else "ready"}")
            if (missing.isNotEmpty()) append("\nStill to set up: ${missing.joinToString()}.")
        }
        button(R.id.toggle_listening).text = if (listening) "Stop listening" else "Start listening for “Hey Blecky”"
        button(R.id.follow_up).text = "Keep the conversation going: " +
            (FOLLOW_UP.firstOrNull { it.first == settings.followUpSeconds }?.second ?: "${settings.followUpSeconds} seconds")
        val brain = BRAINS.firstOrNull { it.first == settings.provider } ?: BRAINS.first()
        button(R.id.provider).text = "Brain: ${brain.second} (tap to change)"
        val keyInfo = KEYS.first { it.first == keyProvider }
        button(R.id.key_for).text = "Add a key for: ${keyInfo.second} (tap to change)"
        findViewById<EditText>(R.id.api_key).hint = "${keyInfo.second} key"
        val saved = KEYS.filter { settings.keyFor(it.first).isNotBlank() }.joinToString { it.second }.ifEmpty { "none yet" }
        findViewById<TextView>(R.id.provider_note).text = "${keyInfo.third}\nKeys saved: $saved."
        button(R.id.speech_mode).text = "I speak: " + (SPEECH.firstOrNull { it.first == settings.speechMode }?.second ?: SPEECH.first().second)
        button(R.id.name_alone).text = "Also wake on just “Bleck”: " + if (settings.wakeOnNameAlone) "on" else "off (fewer accidental wakes)"
        button(R.id.natural_voice).text = blecky.voice.neuralPack()?.let { "Natural voice: ${it.label} (tap for more)" }
            ?: "Download a natural human voice (free, about 67 MB)"
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

    /** Gujarati is read by Google's free speech engine; send the user to install or pick it. */
    private fun setUpGujaratiVoice() {
        scope.launch {
            if (blecky.voice.hasGujaratiVoice()) {
                toast("Gujarati voice is ready")
                blecky.voice.speak("નમસ્તે! હું બ્લેકી છું. કેમ છો?")
                return@launch
            }
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("Gujarati voice")
                .setMessage(
                    "Your phone's speech engine can't read Gujarati yet. It's free to fix:\n\n" +
                        "1. Install or update “Speech Services by Google” from the Play Store.\n" +
                        "2. In the screen that opens next, pick it as the preferred engine.\n" +
                        "3. Tap its gear, then Install voice data, then Gujarati (India).",
                )
                .setPositiveButton("Open voice settings") { _, _ ->
                    openFirst(Intent("com.android.settings.TTS_SETTINGS"))
                }
                .setNeutralButton("Play Store") { _, _ ->
                    openFirst(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.tts")))
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun isXiaomi() = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")

    /** Opens the first of [intents] this phone understands, else Blecky's app info page. */
    private fun openFirst(vararg intents: Intent) {
        for (intent in intents) {
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    companion object {
        private val STRICTNESS = listOf("relaxed" to 0.35f, "normal" to 0.45f, "strict" to 0.55f)
        private val BRAINS = listOf(
            Prefs.PROVIDER_AUTO to "Auto, all free AIs together",
            Prefs.PROVIDER_GEMINI to "Google Gemini only (free)",
            Prefs.PROVIDER_GROQ to "Groq only (free)",
            Prefs.PROVIDER_CEREBRAS to "Cerebras only (free)",
            Prefs.PROVIDER_OPENROUTER to "OpenRouter only (free)",
            Prefs.PROVIDER_CLAUDE to "Claude (paid)",
        )
        private val KEYS = listOf(
            Triple(
                Prefs.PROVIDER_GEMINI, "Google Gemini",
                "Best free brain, great at Hindi and Gujarati. Sign in at aistudio.google.com/apikey with your Google account, tap Create API key, paste it here. No card.",
            ),
            Triple(
                Prefs.PROVIDER_GROQ, "Groq",
                "Free and very fast. Sign up at console.groq.com/keys, create a key, paste it here. No card.",
            ),
            Triple(
                Prefs.PROVIDER_CEREBRAS, "Cerebras",
                "Free and fast. Sign up at cloud.cerebras.ai, open API Keys, paste it here. No card.",
            ),
            Triple(
                Prefs.PROVIDER_OPENROUTER, "OpenRouter",
                "Free open models (Gemma, Nemotron and more, 50 requests a day). Sign up at openrouter.ai/keys. No card.",
            ),
            Triple(Prefs.PROVIDER_CLAUDE, "Claude (paid)", "Only if you have Anthropic credit (console.anthropic.com)."),
        )
        private val SPEECH = listOf(
            "mix" to "English, Hindi and Gujarati",
            "en" to "English only",
            "hi" to "Hindi (and English)",
            "gu" to "Gujarati (and English)",
        )
        private val FOLLOW_UP = listOf(20 to "20 seconds", 45 to "45 seconds", 120 to "2 minutes", 600 to "until I say bye")
    }
}

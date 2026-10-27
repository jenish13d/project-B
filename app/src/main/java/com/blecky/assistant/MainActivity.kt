package com.blecky.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.blecky.assistant.tools.Memory
import com.blecky.assistant.tools.NotificationReader
import com.blecky.assistant.tools.Reminders
import com.blecky.assistant.tools.UserChannel
import com.blecky.assistant.voice.ModelInstaller
import com.blecky.assistant.voice.SpeakerProfile
import com.blecky.assistant.voice.VoiceEnrollment
import com.blecky.assistant.voice.WakeWordService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var toggle: Button
    private lateinit var setup: View
    private var installing = false
    private var enrollment: VoiceEnrollment? = null

    private val onLogChanged: () -> Unit = { renderTranscript() }

    private val screenChannel = object : UserChannel {
        override suspend fun confirm(question: String): Boolean = suspendCancellableCoroutine { cont ->
            AlertDialog.Builder(this@MainActivity)
                .setMessage(question)
                .setPositiveButton("Yes") { _, _ -> if (cont.isActive) cont.resume(true) }
                .setNegativeButton("No") { _, _ -> if (cont.isActive) cont.resume(false) }
                .setOnCancelListener { if (cont.isActive) cont.resume(false) }
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        showOverLockScreen(intent)

        status = findViewById(R.id.status)
        transcript = findViewById(R.id.transcript)
        scroll = findViewById(R.id.transcript_scroll)
        input = findViewById(R.id.input)
        toggle = findViewById(R.id.toggle_listening)
        setup = findViewById(R.id.setup)
        val keyField = findViewById<EditText>(R.id.api_key)

        findViewById<Button>(R.id.save_key).setOnClickListener {
            if (keyField.text.isNotBlank()) {
                blecky.settings.apiKey = keyField.text.toString()
                keyField.text.clear()
                toast("API key saved")
                refresh()
            }
        }
        findViewById<Button>(R.id.grant_permissions).setOnClickListener { requestPermissions(RUNTIME_PERMISSIONS, 1) }
        findViewById<Button>(R.id.grant_overlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.grant_battery).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        }
        findViewById<Button>(R.id.grant_notifications).setOnClickListener {
            if (notificationAccess()) openNotificationAccess() else explainNotificationAccess()
        }
        toggle.setOnClickListener {
            if (WakeWordService.isRunning && blecky.settings.listening) WakeWordService.stop(this) else startListening()
            toggle.postDelayed(::refresh, 500)
        }
        findViewById<Button>(R.id.toggle_setup).setOnClickListener {
            setup.visibility = if (setup.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.enroll_voice).setOnClickListener { if (enrollment != null) enrollment?.cancel() else enrollVoice() }
        findViewById<Button>(R.id.voice_lock).setOnClickListener {
            if (SpeakerProfile.decode(blecky.settings.voiceProfile) == null) {
                enrollVoice()
            } else {
                blecky.settings.voiceLock = !blecky.settings.voiceLock
                restartListening()
                refresh()
            }
        }
        findViewById<Button>(R.id.voice_strictness).setOnClickListener {
            val levels = STRICTNESS.map { it.second }
            val next = levels.firstOrNull { it > blecky.settings.voiceThreshold + 0.001f } ?: levels.first()
            blecky.settings.voiceThreshold = next
            restartListening()
            refresh()
        }
        findViewById<Button>(R.id.change_voice).setOnClickListener {
            scope.launch {
                val name = blecky.voice.nextVoice()
                if (name == null) {
                    toast("No English voices found. Install Google's speech engine from the Play Store.")
                } else {
                    toast("Voice: $name")
                    blecky.voice.speak("Hi! I'm Blecky. How do I sound?")
                }
            }
        }
        findViewById<Button>(R.id.check_in_time).setOnClickListener { pickCheckInTime() }
        findViewById<Button>(R.id.memories).setOnClickListener { showMemories() }
        findViewById<Button>(R.id.send).setOnClickListener { sendTyped() }
        input.setOnEditorActionListener { _, action, _ ->
            (action == EditorInfo.IME_ACTION_SEND).also { if (it) sendTyped() }
        }
        findViewById<View>(R.id.talk).setOnClickListener {
            if (hasMic()) WakeWordService.talk(this) else requestPermissions(RUNTIME_PERMISSIONS, 1)
        }
        Reminders.scheduleDaily(this)
        setup.visibility = if (blecky.settings.apiKey.isBlank() || !hasMic()) View.VISIBLE else View.GONE
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showOverLockScreen(intent)
    }

    override fun onResume() {
        super.onResume()
        ConversationLog.addListener(onLogChanged)
        renderTranscript()
        // Bring the listener back after a reboot or after Android stopped it.
        if (blecky.settings.listening && !WakeWordService.isRunning && hasMic() && ModelInstaller.isInstalled(this)) {
            WakeWordService.listen(this)
        }
        refresh()
    }

    override fun onPause() {
        ConversationLog.removeListener(onLogChanged)
        super.onPause()
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

    private fun showOverLockScreen(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_FROM_WAKE, false) == true) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    private fun startListening() {
        when {
            blecky.settings.apiKey.isBlank() -> toast("Paste your Anthropic API key first")
            !hasMic() -> requestPermissions(RUNTIME_PERMISSIONS, 1)
            !ModelInstaller.isInstalled(this) -> installModels { WakeWordService.listen(this) }
            else -> WakeWordService.listen(this)
        }
    }

    private fun restartListening() {
        if (!WakeWordService.isRunning || !blecky.settings.listening) return
        WakeWordService.stop(this)
        WakeWordService.listen(this)
    }

    private fun installModels(then: () -> Unit) {
        if (installing) return
        installing = true
        scope.launch {
            try {
                ModelInstaller.install(this@MainActivity) { text -> runOnUiThread { status.text = text } }
            } catch (e: Exception) {
                status.text = "Download failed: ${e.message}. Check your internet and try again."
                return@launch
            } finally {
                installing = false
            }
            then()
            refresh()
        }
    }

    /** Records the owner's voice a few times, then turns on the voice lock. */
    private fun enrollVoice() {
        if (!hasMic()) return requestPermissions(RUNTIME_PERMISSIONS, 1)
        if (!ModelInstaller.isInstalled(this)) return installModels(::enrollVoice)
        val wasListening = WakeWordService.isRunning && blecky.settings.listening
        if (wasListening) WakeWordService.stop(this)
        installing = true
        findViewById<Button>(R.id.enroll_voice).text = "Cancel voice setup"
        scope.launch {
            delay(700) // let the listener release the microphone
            val session = VoiceEnrollment(
                this@MainActivity,
                onPrompt = { status.text = it },
                onDone = { voiceprint ->
                    enrollment = null
                    installing = false
                    findViewById<Button>(R.id.enroll_voice).text = "Teach Blecky my voice"
                    if (voiceprint != null) {
                        blecky.settings.voiceProfile = SpeakerProfile.encode(voiceprint)
                        blecky.settings.voiceLock = true
                        toast("Got it. Blecky now only wakes for your voice.")
                    } else {
                        toast("Voice setup stopped")
                    }
                    if (wasListening) WakeWordService.listen(this@MainActivity)
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
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Memory.promptFor(this@MainActivity).trim() }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("What Blecky remembers")
                .setMessage(text)
                .setPositiveButton("OK", null)
                .setNeutralButton("Forget everything") { _, _ ->
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("Delete everything Blecky remembers about you, including your name?")
                        .setPositiveButton("Delete") { _, _ ->
                            Memory.store(this@MainActivity).save(emptyList())
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

    private fun sendTyped() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.text.clear()
        ConversationLog.user(text)
        scope.launch {
            val reply = blecky.brain.ask(text, screenChannel)
            ConversationLog.blecky(reply)
        }
    }

    private fun refresh() {
        if (installing) return
        val missing = buildList {
            if (blecky.settings.apiKey.isBlank()) add("API key")
            if (!hasMic()) add("microphone permission")
            if (!Settings.canDrawOverlays(this@MainActivity)) add("step 2")
            if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) add("step 3")
            if (!notificationAccess()) add("step 4")
        }
        val listening = WakeWordService.isRunning && blecky.settings.listening
        status.text = buildString {
            append(if (listening) "Listening. Say “Hey Blecky”, even with the screen off." else "Not listening right now.")
            if (missing.isNotEmpty()) append("\nStill to set up: ${missing.joinToString()}.")
        }
        toggle.text = if (listening) "Stop listening" else "Start listening for “Hey Blecky”"

        val settings = blecky.settings
        val enrolled = SpeakerProfile.decode(settings.voiceProfile) != null
        findViewById<Button>(R.id.voice_lock).text = when {
            !enrolled -> "Only wake for my voice: teach Blecky your voice first"
            settings.voiceLock -> "Only wake for my voice: on"
            else -> "Only wake for my voice: off"
        }
        val level = STRICTNESS.minByOrNull { kotlin.math.abs(it.second - settings.voiceThreshold) }?.first ?: "normal"
        val last = settings.lastVoiceScore.takeIf { it >= 0 }?.let { " (last try matched ${(it * 100).toInt()}%)" } ?: ""
        findViewById<Button>(R.id.voice_strictness).text = "Voice match: $level$last"
        findViewById<Button>(R.id.check_in_time).text = settings.checkInMinute.let {
            if (it < 0) "Daily check-in: off" else "Daily check-in: %02d:%02d".format(it / 60, it % 60)
        }
    }

    private fun renderTranscript() {
        val entries = ConversationLog.all()
        transcript.text = if (entries.isEmpty()) {
            "Say “Hey Blecky” or “Bleck”, wait for Blecky to answer, then ask anything: “What's on my calendar tomorrow?”, " +
                "“Remind me to call Mom at 6”, “Text Sam I'm running late”, “Open YouTube”."
        } else {
            entries.joinToString("\n\n") { (if (it.fromUser) "You: " else "Blecky: ") + it.text }
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun notificationAccess(): Boolean {
        val component = ComponentName(this, NotificationReader::class.java).flattenToString()
        return Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(component) == true
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_FROM_WAKE = "from_wake"

        private val STRICTNESS = listOf("relaxed" to 0.35f, "normal" to 0.45f, "strict" to 0.55f)

        private val RUNTIME_PERMISSIONS = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.READ_CALENDAR)
            add(Manifest.permission.WRITE_CALENDAR)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
}

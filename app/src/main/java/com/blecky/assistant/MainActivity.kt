package com.blecky.assistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import com.blecky.assistant.tools.Reminders
import com.blecky.assistant.tools.UserChannel
import com.blecky.assistant.voice.ModelInstaller
import com.blecky.assistant.voice.VoiceState
import com.blecky.assistant.voice.WakeWordService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** The HUD: Blecky's particle orb, what she's doing, the conversation, and a box to type in. */
class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var status: TextView
    private lateinit var pill: TextView
    private lateinit var transcript: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private var typing = false

    private val onLogChanged: () -> Unit = { renderTranscript() }

    private val statusTicker = object : Runnable {
        override fun run() {
            renderStatus()
            status.postDelayed(this, 250)
        }
    }

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
        pill = findViewById(R.id.listening_pill)
        transcript = findViewById(R.id.transcript)
        scroll = findViewById(R.id.transcript_scroll)
        input = findViewById(R.id.input)

        findViewById<View>(R.id.open_settings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.orb).apply {
            setOnClickListener { talk() }
            setOnLongClickListener {
                blecky.voice.stopSpeaking()
                true
            }
        }
        findViewById<View>(R.id.talk).setOnClickListener { talk() }
        findViewById<View>(R.id.send).setOnClickListener { sendTyped() }
        input.setOnEditorActionListener { _, action, _ ->
            (action == EditorInfo.IME_ACTION_SEND).also { if (it) sendTyped() }
        }

        Reminders.scheduleDaily(this)
        if (blecky.settings.apiKey.isBlank()) startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showOverLockScreen(intent)
    }

    override fun onResume() {
        super.onResume()
        ConversationLog.addListener(onLogChanged)
        renderTranscript()
        status.post(statusTicker)
        // Bring the listener back after a reboot or after Android stopped it.
        if (blecky.settings.listening && !WakeWordService.isRunning && Permissions.hasMic(this) && ModelInstaller.isInstalled(this)) {
            WakeWordService.listen(this)
        }
    }

    override fun onPause() {
        ConversationLog.removeListener(onLogChanged)
        status.removeCallbacks(statusTicker)
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (Permissions.hasMic(this)) talk()
    }

    private fun showOverLockScreen(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_FROM_WAKE, false) == true) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    private fun talk() {
        when {
            blecky.settings.apiKey.isBlank() -> startActivity(Intent(this, SettingsActivity::class.java))
            !Permissions.hasMic(this) -> requestPermissions(Permissions.RUNTIME, 1)
            else -> WakeWordService.talk(this)
        }
    }

    private fun sendTyped() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.text.clear()
        ConversationLog.user(text)
        typing = true
        scope.launch {
            val reply = try {
                blecky.brain.ask(text, screenChannel)
            } finally {
                typing = false
                if (VoiceState.mode == VoiceState.Mode.THINKING) {
                    VoiceState.mode = if (WakeWordService.isRunning) VoiceState.Mode.WAITING else VoiceState.Mode.OFF
                }
            }
            ConversationLog.blecky(reply)
        }
    }

    private fun renderStatus() {
        val listening = WakeWordService.isRunning && blecky.settings.listening
        // Typed chats don't go through the voice service, so the screen drives the orb for them.
        if (typing) {
            VoiceState.mode = VoiceState.Mode.THINKING
        } else if (!WakeWordService.isRunning && VoiceState.mode != VoiceState.Mode.SPEAKING) {
            VoiceState.mode = VoiceState.Mode.OFF
        }
        status.text = when (VoiceState.mode) {
            VoiceState.Mode.OFF -> "TAP THE ORB TO TALK"
            VoiceState.Mode.WAITING -> "SAY “HEY BLECKY”"
            VoiceState.Mode.LISTENING -> "LISTENING…"
            VoiceState.Mode.THINKING -> "THINKING…"
            VoiceState.Mode.SPEAKING -> "SPEAKING"
        }
        pill.text = if (listening) "● ALWAYS LISTENING" else "○ WAKE WORD OFF"
        pill.setTextColor(getColor(if (listening) R.color.hud_cyan else R.color.hud_muted))
    }

    private fun renderTranscript() {
        val entries = ConversationLog.all().takeLast(40)
        if (entries.isEmpty()) {
            transcript.text = "Tap the orb or say “Hey Blecky”, then just talk. Try “What's on my calendar tomorrow?”, " +
                "“Remind me to call Mom at 6”, or “Call Jayesh on Jio”."
            transcript.setTextColor(getColor(R.color.hud_muted))
            return
        }
        transcript.setTextColor(getColor(R.color.hud_text))
        val text = SpannableStringBuilder()
        entries.forEach { entry ->
            if (text.isNotEmpty()) text.append("\n\n")
            val start = text.length
            if (entry.fromUser) {
                text.append("YOU  ").append(entry.text)
                text.setSpan(ForegroundColorSpan(getColor(R.color.hud_muted)), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(RelativeSizeSpan(0.75f), start, start + 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                text.append("BLECKY  ").append(entry.text)
                text.setSpan(ForegroundColorSpan(getColor(R.color.hud_cyan)), start, start + 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(RelativeSizeSpan(0.75f), start, start + 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(StyleSpan(Typeface.BOLD), start, start + 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(ForegroundColorSpan(Color.WHITE), start + 6, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        transcript.text = text
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    companion object {
        const val EXTRA_FROM_WAKE = "from_wake"
    }
}

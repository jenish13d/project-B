package com.blecky.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import com.blecky.assistant.tools.NotificationReader
import com.blecky.assistant.tools.UserChannel
import com.blecky.assistant.voice.ModelInstaller
import com.blecky.assistant.voice.WakeWordService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
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
    private var installing = false

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
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        toggle.setOnClickListener {
            if (WakeWordService.isRunning && blecky.settings.listening) WakeWordService.stop(this) else startListening()
            toggle.postDelayed(::refresh, 500)
        }
        findViewById<Button>(R.id.send).setOnClickListener { sendTyped() }
        input.setOnEditorActionListener { _, action, _ ->
            (action == EditorInfo.IME_ACTION_SEND).also { if (it) sendTyped() }
        }
        findViewById<View>(R.id.talk).setOnClickListener {
            if (hasMic()) WakeWordService.talk(this) else requestPermissions(RUNTIME_PERMISSIONS, 1)
        }
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
            !ModelInstaller.isInstalled(this) -> installModelThenListen()
            else -> WakeWordService.listen(this)
        }
    }

    private fun installModelThenListen() {
        if (installing) return
        installing = true
        scope.launch {
            try {
                ModelInstaller.install(this@MainActivity) { pct ->
                    runOnUiThread { status.text = "Downloading Blecky's offline ear (about 40 MB): $pct%" }
                }
                WakeWordService.listen(this@MainActivity)
            } catch (e: Exception) {
                status.text = "Download failed: ${e.message}. Check your internet and try again."
                return@launch
            } finally {
                installing = false
            }
            refresh()
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
    }

    private fun renderTranscript() {
        val entries = ConversationLog.all()
        transcript.text = if (entries.isEmpty()) {
            "Say “Hey Blecky”, wait for the beep, then ask anything: “What's on my calendar tomorrow?”, " +
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

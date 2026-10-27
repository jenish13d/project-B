package com.blecky.assistant.updates

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import com.blecky.assistant.BuildConfig
import com.blecky.assistant.Permissions
import com.blecky.assistant.blecky
import com.blecky.assistant.voice.ModelInstaller
import com.blecky.assistant.voice.VoiceState
import com.blecky.assistant.voice.WakeWordService
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Crash reports and status for Claude to read. Nothing leaves the phone by itself: the user sends
 * a report as a GitHub issue they can read before submitting. Reports hold app state and Blecky's
 * own log lines, never conversations.
 */
object Diagnostics {
    private const val ISSUES = "https://github.com/jenish13d/project-B/issues/new"

    /** Browsers and GitHub reject much longer links. */
    private const val MAX_URL = 7_500

    private fun crashFile(context: Context) = File(context.filesDir, "last_crash.txt")

    /** Saves any crash to a file so it can be reported on the next launch, then lets Android handle it. */
    fun installCrashHandler(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                crashFile(context).writeText(
                    "Crashed ${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)} on thread ${thread.name}, " +
                        "Blecky ${BuildConfig.VERSION_NAME}\n\n${trace.lines().take(60).joinToString("\n")}",
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun lastCrash(context: Context): String? = crashFile(context).takeIf { it.exists() }?.readText()

    fun clearCrash(context: Context) {
        crashFile(context).delete()
    }

    /** A readable snapshot of how Blecky is doing on this phone. */
    fun report(context: Context): String {
        val app = context.blecky
        val settings = app.settings
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it > 0 }?.let { "%.1f°C".format(it / 10.0) }
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val status = linkedMapOf(
            "Blecky" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "Phone" to "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Memory" to "%.1f GB".format(app.brain.offline.models.phoneRamGb()),
            "Battery" to "${level ?: "?"}%, temperature ${temperature ?: "unknown"}",
            "Listening" to "service ${if (WakeWordService.isRunning) "running" else "stopped"}, wanted ${settings.listening}, now ${VoiceState.mode}",
            "Wake models" to if (ModelInstaller.isInstalled(context)) "installed" else "missing",
            "Brain" to "${settings.provider}, on-phone AI ${app.brain.offline.models.active(settings.offlineModel)?.id ?: "none"}, " +
                "online key ${if (settings.activeKey.isBlank()) "none" else "set"}",
            "Voice" to (app.voice.neuralPack()?.id ?: "phone voice"),
            "Permissions" to "mic ${Permissions.hasMic(context)}, notifications ${Permissions.notificationAccess(context)}",
        )
        return format(status, lastCrash(context), ownLogs())
    }

    fun format(status: Map<String, String>, crash: String?, logs: String): String = buildString {
        status.forEach { (k, v) -> append("- **$k:** $v\n") }
        if (crash != null) append("\n### Last crash\n```\n$crash\n```\n")
        if (logs.isNotBlank()) append("\n### Recent warnings and errors\n```\n$logs\n```\n")
    }

    /** Blecky's own warnings and errors from the system log (an app may always read its own). */
    private fun ownLogs(): String = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "400", "--pid=${Process.myPid()}", "*:W"))
        process.inputStream.bufferedReader().use { it.readLines() }
            .filter { it.isNotBlank() && !it.startsWith("-----") }
            .takeLast(40)
            .joinToString("\n")
    }.getOrDefault("")

    /** A link that opens a new GitHub issue with the report filled in, cut to fit a URL. */
    fun issueUrl(title: String, body: String): String {
        fun url(text: String) = "$ISSUES?title=${URLEncoder.encode(title, "UTF-8")}&body=${URLEncoder.encode(text, "UTF-8")}"
        fun cut(length: Int) = if (length >= body.length) body else body.take(length) + "\n```\n(cut to fit)"
        var keep = body.length
        while (url(cut(keep)).length > MAX_URL && keep > 200) keep = (keep * 0.85).toInt()
        return url(cut(keep))
    }
}

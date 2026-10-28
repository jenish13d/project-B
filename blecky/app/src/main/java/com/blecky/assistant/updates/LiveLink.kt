package com.blecky.assistant.updates

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blecky.assistant.BleckyApp
import com.blecky.assistant.BuildConfig
import com.blecky.assistant.R
import com.blecky.assistant.SettingsActivity
import com.blecky.assistant.blecky
import com.blecky.assistant.voice.WakeWordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Blecky's live link to Claude, through GitHub issues on the user's own repo. With a GitHub token
 * that can only manage issues, she keeps one "Blecky live status" issue up to date, files each crash
 * as its own issue and comments when something goes wrong, so Claude can check on her and fix things.
 *
 * The repo is public, so nothing personal is ever posted: no conversations, contacts or log lines,
 * only app state and crash stack traces.
 */
object LiveLink {
    private const val API = "https://api.github.com/repos/jenish13d/project-B"
    const val STATUS_TITLE = "Blecky live status"
    private const val MAX_EVENTS_PER_DAY = 30

    private fun prefs(context: Context) = context.getSharedPreferences("live_link", Context.MODE_PRIVATE)

    fun token(context: Context) = prefs(context).getString("token", "") ?: ""

    fun setToken(context: Context, token: String) {
        prefs(context).edit().putString("token", token.trim()).remove("status_issue").apply()
        schedule(context)
    }

    fun isOn(context: Context) = token(context).isNotBlank()

    /** Runs the check-in every 30 minutes while there's a network, and once right now. */
    fun schedule(context: Context) {
        val work = WorkManager.getInstance(context)
        if (!isOn(context)) {
            work.cancelUniqueWork(PERIODIC)
            return
        }
        val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        work.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CheckInWorker>(30, TimeUnit.MINUTES).setConstraints(online).build(),
        )
        work.enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<CheckInWorker>().setConstraints(online).build())
    }

    /** Posts a short note on the status issue, a few times a day at most for the same kind of thing. */
    fun event(context: Context, kind: String, text: String) {
        if (!isOn(context)) return
        val p = prefs(context)
        val now = System.currentTimeMillis()
        if (now - p.getLong("event_$kind", 0) < 30 * 60_000L) return
        val day = now / 86_400_000L
        val count = if (p.getLong("event_day", -1) == day) p.getInt("event_count", 0) else 0
        if (count >= MAX_EVENTS_PER_DAY) return
        p.edit().putLong("event_$kind", now).putLong("event_day", day).putInt("event_count", count + 1).apply()
        context.blecky.background {
            runCatching { comment(context, statusIssue(context), "**${stamp()}** · `$kind` · $text") }
                .onFailure { Log.w("Blecky", "Live link event failed", it) }
        }
    }

    /** Checks the token works and the status issue exists; returns its number. */
    suspend fun test(context: Context): Int = withContext(Dispatchers.IO) { statusIssue(context).also { updateStatus(context) } }

    /** The check-in: report a crash, notice a stopped service, refresh the status, look for updates. */
    suspend fun checkIn(context: Context) = withContext(Dispatchers.IO) {
        if (!isOn(context)) return@withContext
        Diagnostics.lastCrash(context)?.let { crash ->
            val firstLine = crash.lines().firstOrNull { it.contains("Exception") || it.contains("Error") }?.trim()?.take(80) ?: "crash"
            request(context, "POST", "$API/issues", JSONObject().put("title", "[Blecky crash] $firstLine").put("body", publicStatus(context) + "\n```\n$crash\n```"))
            Diagnostics.clearCrash(context)
        }
        val settings = context.blecky.settings
        if (settings.listening && !WakeWordService.isRunning) {
            event(context, "stopped", "Listening was on but the service wasn't running; asked the user to tap to restart.")
            WakeWordService.notifyRestartNeeded(context)
        }
        updateStatus(context)
        if (Updater.due(context)) runCatching { Updater.check(context) }.getOrNull()?.let { notifyUpdate(context, it) }
    }

    private fun updateStatus(context: Context) {
        request(context, "PATCH", "$API/issues/${statusIssue(context)}", JSONObject().put("body", publicStatus(context)))
    }

    /** The status that's safe to post publicly: app state only. */
    fun publicStatus(context: Context): String =
        "Updated **${stamp()}** by Blecky ${BuildConfig.VERSION_NAME}. Claude reads this to check on her.\n\n" +
            Diagnostics.format(Diagnostics.status(context), crash = null, logs = "")

    /** Synchronized so check-ins and events starting together don't each create an issue. */
    @Synchronized
    private fun statusIssue(context: Context): Int {
        val p = prefs(context)
        p.getInt("status_issue", 0).takeIf { it > 0 }?.let { return it }
        val open = JSONArray(request(context, "GET", "$API/issues?state=open&per_page=100", null))
        val existing = pickStatusIssue((0 until open.length()).map { open.getJSONObject(it) })
        val number = existing
            ?: JSONObject(request(context, "POST", "$API/issues", JSONObject().put("title", STATUS_TITLE).put("body", "Starting up…"))).getInt("number")
        p.edit().putInt("status_issue", number).apply()
        return number
    }

    /** The oldest open status issue, so every check-in settles on the same one. */
    fun pickStatusIssue(issues: List<JSONObject>): Int? =
        issues.filter { it.optString("title") == STATUS_TITLE && !it.has("pull_request") }.minOfOrNull { it.getInt("number") }

    private fun comment(context: Context, issue: Int, text: String) {
        request(context, "POST", "$API/issues/$issue/comments", JSONObject().put("body", text))
    }

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }

    private fun request(context: Context, method: String, url: String, body: JSONObject?): String {
        val request = Request.Builder()
            .url(url)
            .method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer ${token(context)}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Blecky/${BuildConfig.VERSION_NAME}")
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: ""
            val status = response.code
            if (status == 401 || status == 403) throw IllegalStateException("GitHub refused the token (HTTP $status). It needs Issues: Read and write on project-B.")
            if (status == 404 || status == 410) prefs(context).edit().remove("status_issue").apply()
            if (status !in 200..299) throw IllegalStateException("GitHub said HTTP $status: ${text.take(200)}")
            return text
        }
    }

    private fun notifyUpdate(context: Context, update: Updater.Update) {
        val open = PendingIntent.getActivity(
            context, 3,
            Intent(context, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_CHECK_UPDATES, true),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, BleckyApp.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Blecky ${update.versionName} is ready")
            .setContentText(update.notes.ifBlank { "Tap to update" })
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(4, notification)
    }

    private fun stamp() = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z"))

    private const val PERIODIC = "blecky-live-link"
    private const val NOW = "blecky-live-link-now"
}

class CheckInWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        LiveLink.checkIn(applicationContext)
        Result.success()
    } catch (e: Exception) {
        Log.w("Blecky", "Live link check-in failed", e)
        Result.retry()
    }
}

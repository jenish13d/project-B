package com.blecky.assistant.updates

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.blecky.assistant.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates Blecky from her GitHub release: every push to blecky/ is built by GitHub Actions and
 * published there, so new versions reach the phone without sending APK files around.
 */
object Updater {
    private const val RELEASE = "https://github.com/jenish13d/project-B/releases/download/blecky-latest"
    const val INFO_URL = "$RELEASE/update.json"
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    data class Update(val versionCode: Int, val versionName: String, val notes: String, val apkUrl: String, val sha256: String)

    fun parse(json: String): Update {
        val o = JSONObject(json)
        return Update(
            o.getInt("versionCode"),
            o.getString("versionName"),
            o.optString("notes"),
            o.getString("apkUrl"),
            o.getString("sha256").lowercase(),
        )
    }

    fun isNewer(update: Update, installedVersionCode: Int = BuildConfig.VERSION_CODE) = update.versionCode > installedVersionCode

    /** Whether it's time for the periodic check (at most every few hours). */
    fun due(context: Context): Boolean =
        System.currentTimeMillis() - prefs(context).getLong("last_check", 0) > CHECK_EVERY_MS

    /** The newer version on GitHub, or null if this one is current. */
    suspend fun check(context: Context): Update? = withContext(Dispatchers.IO) {
        prefs(context).edit().putLong("last_check", System.currentTimeMillis()).apply()
        val connection = URL(INFO_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            if (connection.responseCode != 200) throw IllegalStateException("No published build yet (HTTP ${connection.responseCode}).")
            parse(connection.inputStream.bufferedReader().use { it.readText() }).takeIf { isNewer(it) }
        } finally {
            connection.disconnect()
        }
    }

    /** Downloads [update], checks it, and asks Android to install it (Android shows its own confirmation). */
    suspend fun install(context: Context, update: Update, progress: (String) -> Unit) {
        val file = download(context, update, progress)
        progress("Checking the download…")
        val actual = withContext(Dispatchers.IO) { sha256(file) }
        if (actual != update.sha256) {
            file.delete()
            throw IllegalStateException("The download was damaged. Try again.")
        }
        progress("Installing Blecky ${update.versionName}…")
        withContext(Dispatchers.IO) {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply { setAppPackageName(context.packageName) }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("blecky.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val callback = PendingIntent.getBroadcast(
                    context, sessionId, Intent(context, InstallResultReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            }
        }
    }

    private suspend fun download(context: Context, update: Update, progress: (String) -> Unit): File {
        val target = File(context.getExternalFilesDir("updates"), "blecky-${update.versionCode}.apk")
        context.getExternalFilesDir("updates")?.listFiles()?.filter { it != target }?.forEach { it.delete() }
        if (target.exists() && withContext(Dispatchers.IO) { sha256(target) } == update.sha256) return target
        target.delete()
        val manager = context.getSystemService(DownloadManager::class.java)
        val id = manager.enqueue(
            DownloadManager.Request(Uri.parse(update.apkUrl))
                .setTitle("Blecky ${update.versionName}")
                .setDestinationUri(Uri.fromFile(target)),
        )
        while (true) {
            val state = manager.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (!c.moveToFirst()) return@use null
                Triple(
                    c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                )
            } ?: throw IllegalStateException("The download was cancelled.")
            when (state.first) {
                DownloadManager.STATUS_SUCCESSFUL -> return target
                DownloadManager.STATUS_FAILED -> throw IllegalStateException("The download failed. Check your internet.")
                else -> if (state.third > 0) progress("Downloading Blecky ${update.versionName}: ${state.second * 100 / state.third}%")
            }
            delay(1_000)
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
}

/** Android reports the install's progress here; the first report asks the user to confirm. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w("Blecky", "Update failed ($status): $message")
                Toast.makeText(context, "Update didn't install: ${message ?: status}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

package com.blecky.assistant.updates

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The dialogs for updates and reports, shared by the main and settings screens. */
object UpdateUi {
    /** Checks GitHub for a newer Blecky; [quiet] skips the "you're up to date" message. */
    fun check(activity: Activity, scope: CoroutineScope, quiet: Boolean, progress: (String) -> Unit = {}) {
        scope.launch {
            val update = runCatching { Updater.check(activity) }
                .onFailure { if (!quiet) toast(activity, "Couldn't check for updates: ${it.message}") }
                .getOrNull()
            if (update == null) {
                if (!quiet) toast(activity, "Blecky is up to date")
                return@launch
            }
            AlertDialog.Builder(activity)
                .setTitle("Blecky ${update.versionName} is ready")
                .setMessage(update.notes.ifBlank { "A new version is available." })
                .setPositiveButton("Update now") { _, _ -> install(activity, scope, update, progress) }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    private fun install(activity: Activity, scope: CoroutineScope, update: Updater.Update, progress: (String) -> Unit) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setMessage("Android needs your OK once to let Blecky install her own updates. Turn on “Allow from this source”, come back, and tap Update again.")
                .setPositiveButton("Open setting") { _, _ ->
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        scope.launch {
            runCatching { Updater.install(activity, update) { text -> activity.runOnUiThread { progress(text) } } }
                .onFailure { toast(activity, "Update failed: ${it.message}") }
        }
    }

    /** Offers to send the last crash, if there was one, then forgets it either way. */
    fun offerCrashReport(activity: Activity) {
        Diagnostics.lastCrash(activity) ?: return
        AlertDialog.Builder(activity)
            .setTitle("Blecky crashed last time")
            .setMessage("Send the crash report to Claude so it can be fixed? You'll see it on GitHub before submitting.")
            .setPositiveButton("Send") { _, _ -> sendReport(activity, "Blecky crashed") }
            .setNegativeButton("Not now") { _, _ -> Diagnostics.clearCrash(activity) }
            .show()
    }

    fun sendReport(activity: Activity, title: String = "Blecky report") {
        val url = Diagnostics.issueUrl("[Blecky] $title", Diagnostics.report(activity))
        Diagnostics.clearCrash(activity)
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast(activity, "No browser found to open GitHub") }
    }

    private fun toast(activity: Activity, text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
}

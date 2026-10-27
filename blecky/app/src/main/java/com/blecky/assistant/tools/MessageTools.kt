package com.blecky.assistant.tools

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Keeps the most recent notifications (emails, chats, texts) so Blecky can read them out. */
class NotificationReader : NotificationListenerService() {
    data class Item(val app: String, val pkg: String, val title: String, val text: String, val time: Long)

    override fun onListenerConnected() {
        connected = true
        runCatching { activeNotifications }.getOrNull()?.sortedBy { it.postTime }?.forEach(::record)
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = record(sbn)

    private fun record(sbn: StatusBarNotification) {
        if (sbn.isOngoing || sbn.packageName == packageName) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.take(400) ?: ""
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        synchronized(recent) {
            recent.removeAll { it.pkg == sbn.packageName && it.title == title && it.text == text }
            recent.addLast(Item(app, sbn.packageName, title, text, sbn.postTime))
            while (recent.size > 60) recent.removeFirst()
        }
    }

    companion object {
        private val recent = ArrayDeque<Item>()

        @Volatile
        var connected = false
            private set

        fun recent(): List<Item> = synchronized(recent) { recent.toList() }
    }
}

fun messageTools(): List<BleckyTool> = listOf(ReadNotifications, ComposeEmail)

object ReadNotifications : BleckyTool(
    "read_notifications",
    "Read the user's recent notifications, newest first: new emails (Gmail etc.), WhatsApp/SMS messages, and other alerts.",
    listOf(
        Param("app", "string", "Only from this app, e.g. Gmail, WhatsApp, Messages", required = false),
        Param("limit", "integer", "How many to return (default 10)", required = false),
    ),
) {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        val component = ComponentName(ctx.context, NotificationReader::class.java).flattenToString()
        val enabled = Settings.Secure.getString(ctx.context.contentResolver, "enabled_notification_listeners")
            ?.contains(component) == true
        if (!enabled) throw ToolError("Blecky can't read notifications yet. Open the Blecky app and tap step 4.")

        val app = args.optString("app")
        val items = NotificationReader.recent()
            .filter { app == null || it.app.contains(app, ignoreCase = true) || it.pkg.contains(app, ignoreCase = true) }
            .sortedByDescending { it.time }
            .take((args.optInt("limit") ?: 10).coerceIn(1, 30))
        if (items.isEmpty()) return if (app == null) "No recent notifications." else "No recent notifications from $app."
        val format = DateTimeFormatter.ofPattern("EEE HH:mm").withZone(ZoneId.systemDefault())
        return items.joinToString("\n") { "${format.format(Instant.ofEpochMilli(it.time))} ${it.app} - ${it.title}: ${it.text}" }
    }
}

object ComposeEmail : BleckyTool(
    "compose_email",
    "Open a ready-to-send email draft in the user's email app (they tap send). 'to' can be a contact name or an email address.",
    listOf(
        Param("to", "string", "Recipient contact name or email address"),
        Param("subject", "string", "Subject line"),
        Param("body", "string", "Email body text"),
    ),
) {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        val to = args.string("to")
        val address = if ('@' in to) {
            to
        } else {
            val matches = ctx.context.findContacts(to, email = true)
            when {
                matches.isEmpty() -> throw ToolError("No contact named '$to' with an email address.")
                matches.map { it.name }.distinct().size > 1 ->
                    throw ToolError("Several contacts match '$to': ${matches.take(6).joinToString { it.name }}. Ask which one.")
                else -> matches.first().value
            }
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(address))
            .putExtra(Intent.EXTRA_SUBJECT, args.string("subject"))
            .putExtra(Intent.EXTRA_TEXT, args.string("body"))
        return ctx.context.launch(intent, "Email draft to $address is open; the user needs to tap send.")
    }
}

package com.blecky.assistant.tools

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.blecky.assistant.BleckyApp
import com.blecky.assistant.MainActivity
import com.blecky.assistant.R
import com.blecky.assistant.blecky
import com.blecky.assistant.voice.WakeWordService
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object Reminders {
    private fun store(context: Context) = JsonListStore(File(context.filesDir, "reminders.json"))

    /** Works out when a reminder fires from either an ISO local time or minutes from now. */
    fun resolveTime(at: String?, inMinutes: Int?, now: LocalDateTime): LocalDateTime {
        val time = when {
            inMinutes != null -> now.plusMinutes(inMinutes.toLong())
            at != null -> try {
                LocalDateTime.parse(at.trim().replace(' ', 'T').take(19))
            } catch (e: DateTimeParseException) {
                throw ToolError("I couldn't understand the time '$at'.")
            }
            else -> throw ToolError("When should I remind you?")
        }
        if (!time.isAfter(now)) throw ToolError("That time has already passed.")
        return time
    }

    fun add(context: Context, text: String, at: LocalDateTime): Int {
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val id = store(context).add(JSONObject().put("text", text).put("at", millis))
        schedule(context, id, text, millis)
        return id
    }

    fun list(context: Context) = store(context).load()

    fun cancel(context: Context, id: Int): JSONObject? {
        val removed = store(context).remove(id) ?: return null
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, id, removed.getString("text")))
        return removed
    }

    fun rescheduleAll(context: Context) {
        val soon = System.currentTimeMillis() + 10_000
        list(context).forEach { schedule(context, it.getInt("id"), it.getString("text"), maxOf(it.getLong("at"), soon)) }
    }

    private fun schedule(context: Context, id: Int, text: String, millis: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(context, id, text)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }

    private fun pendingIntent(context: Context, id: Int, text: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).putExtra("id", id).putExtra("text", text)
        return PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    internal fun fire(context: Context, id: Int, text: String) {
        store(context).remove(id)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, BleckyApp.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Reminder")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(1000 + id, notification)
        if (WakeWordService.isRunning) WakeWordService.say(context, "Reminder: $text")
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.fire(context, intent.getIntExtra("id", 0), intent.getStringExtra("text") ?: "Reminder")
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Reminders.rescheduleAll(context)
        if (context.blecky.settings.listening) WakeWordService.notifyRestartNeeded(context)
    }
}

fun reminderTools(): List<BleckyTool> = listOf(SetReminder, ListReminders, CancelReminder)

object SetReminder : BleckyTool(
    "set_reminder",
    "Remind the user about something later: Blecky shows a notification and says it out loud. Give either 'at' or 'in_minutes'.",
    listOf(
        Param("text", "string", "What to remind the user about"),
        Param("at", "string", "Local date-time in ISO format, e.g. 2026-10-27T17:30", required = false),
        Param("in_minutes", "integer", "Minutes from now", required = false),
    ),
) {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        val time = Reminders.resolveTime(args.optString("at"), args.optInt("in_minutes"), LocalDateTime.now())
        val id = Reminders.add(ctx.context, args.string("text"), time)
        return "Reminder #$id set for ${time.format(DateTimeFormatter.ofPattern("EEEE d MMMM 'at' HH:mm"))}."
    }
}

object ListReminders : BleckyTool("list_reminders", "List upcoming reminders with their ids.") {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        val items = Reminders.list(ctx.context)
        if (items.isEmpty()) return "No upcoming reminders."
        val format = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")
        return items.sortedBy { it.getLong("at") }.joinToString("\n") {
            val at = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it.getLong("at")), ZoneId.systemDefault())
            "#${it.getInt("id")} ${at.format(format)}: ${it.getString("text")}"
        }
    }
}

object CancelReminder : BleckyTool(
    "cancel_reminder", "Cancel an upcoming reminder by id (use list_reminders first).",
    listOf(Param("id", "integer", "The reminder id")),
) {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        val removed = Reminders.cancel(ctx.context, args.int("id")) ?: throw ToolError("No reminder with that id.")
        return "Cancelled reminder: ${removed.getString("text")}"
    }
}

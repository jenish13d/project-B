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
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Timed events: plain reminders the user asked for, check-ins Blecky scheduled itself to follow up
 * on something, and the optional daily check-in.
 */
object Reminders {
    const val KIND_REMINDER = "reminder"
    const val KIND_CHECK_IN = "checkin"
    private const val KIND_DAILY = "daily"
    private const val DAILY_REQUEST_CODE = 999_999

    const val DAILY_PROMPT =
        "Daily check-in. Greet the user for this time of day, mention anything useful for today " +
            "(look at their calendar and reminders), and ask how they're doing. About three sentences."

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
            else -> throw ToolError("When should this happen?")
        }
        if (!time.isAfter(now)) throw ToolError("That time has already passed.")
        return time
    }

    /** The next time the daily check-in fires: today if still ahead, otherwise tomorrow. */
    fun nextDaily(minuteOfDay: Int, now: LocalDateTime): LocalDateTime {
        val today = now.toLocalDate().atTime(LocalTime.of(minuteOfDay / 60, minuteOfDay % 60))
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    fun add(context: Context, text: String, at: LocalDateTime, kind: String = KIND_REMINDER): Int {
        val millis = at.toMillis()
        val id = store(context).add(JSONObject().put("text", text).put("at", millis).put("kind", kind))
        schedule(context, id, text, kind, millis)
        return id
    }

    fun list(context: Context) = store(context).load()

    fun cancel(context: Context, id: Int): JSONObject? {
        val removed = store(context).remove(id) ?: return null
        context.getSystemService(AlarmManager::class.java)
            .cancel(pendingIntent(context, id, removed.getString("text"), removed.optString("kind", KIND_REMINDER)))
        return removed
    }

    fun rescheduleAll(context: Context) {
        val soon = System.currentTimeMillis() + 10_000
        list(context).forEach {
            schedule(context, it.getInt("id"), it.getString("text"), it.optString("kind", KIND_REMINDER), maxOf(it.getLong("at"), soon))
        }
        scheduleDaily(context)
    }

    /** (Re)arms the daily check-in from settings, or cancels it when turned off. */
    fun scheduleDaily(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(context, DAILY_REQUEST_CODE, "", KIND_DAILY)
        alarms.cancel(pi)
        val minute = context.blecky.settings.checkInMinute
        if (minute < 0) return
        setAlarm(alarms, nextDaily(minute, LocalDateTime.now()).toMillis(), pi)
    }

    private fun schedule(context: Context, id: Int, text: String, kind: String, millis: Long) {
        setAlarm(context.getSystemService(AlarmManager::class.java), millis, pendingIntent(context, id, text, kind))
    }

    private fun setAlarm(alarms: AlarmManager, millis: Long, pi: PendingIntent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }

    private fun pendingIntent(context: Context, id: Int, text: String, kind: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra("id", id)
            .putExtra("text", text)
            .putExtra("kind", kind)
        return PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    internal fun fire(context: Context, id: Int, text: String, kind: String) {
        val live = WakeWordService.isRunning
        when (kind) {
            KIND_DAILY -> {
                scheduleDaily(context)
                if (live) WakeWordService.checkIn(context, DAILY_PROMPT) else notify(context, 999, "Blecky", "Tap to catch up with Blecky")
            }
            KIND_CHECK_IN -> {
                store(context).remove(id)
                if (live) {
                    WakeWordService.checkIn(context, "Follow-up you scheduled earlier: $text. Open the conversation about it naturally.")
                } else {
                    notify(context, 1000 + id, "Blecky wants to check in", text)
                }
            }
            else -> {
                store(context).remove(id)
                notify(context, 1000 + id, "Reminder", text)
                if (live) WakeWordService.say(context, "Reminder: $text")
            }
        }
    }

    private fun notify(context: Context, notificationId: Int, title: String, text: String) {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, BleckyApp.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    private fun LocalDateTime.toMillis() = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.fire(
            context,
            intent.getIntExtra("id", 0),
            intent.getStringExtra("text") ?: "Reminder",
            intent.getStringExtra("kind") ?: Reminders.KIND_REMINDER,
        )
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Reminders.rescheduleAll(context)
        if (context.blecky.settings.listening) WakeWordService.notifyRestartNeeded(context)
    }
}

fun reminderTools(): List<BleckyTool> = listOf(SetReminder, ScheduleCheckIn, ListReminders, CancelReminder)

private val timeParams = listOf(
    Param("at", "string", "Local date-time in ISO format, e.g. 2026-10-27T17:30", required = false),
    Param("in_minutes", "integer", "Minutes from now", required = false),
)

private fun LocalDateTime.spoken() = format(DateTimeFormatter.ofPattern("EEEE d MMMM 'at' HH:mm"))

object SetReminder : BleckyTool(
    "set_reminder",
    "Remind the user about something later: Blecky shows a notification and says it out loud. Give either 'at' or 'in_minutes'.",
    listOf(Param("text", "string", "What to remind the user about")) + timeParams,
) {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        val time = Reminders.resolveTime(args.optString("at"), args.optInt("in_minutes"), LocalDateTime.now())
        val id = Reminders.add(ctx.context, args.string("text"), time)
        return "Reminder #$id set for ${time.spoken()}."
    }
}

object ScheduleCheckIn : BleckyTool(
    "schedule_check_in",
    "Plan to start a conversation with the user yourself later, e.g. ask how their exam or interview went, " +
        "or follow up on something they mentioned. At that time you will speak first. Give either 'at' or 'in_minutes'.",
    listOf(Param("topic", "string", "What to follow up on, with enough context to open the conversation")) + timeParams,
) {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        val time = Reminders.resolveTime(args.optString("at"), args.optInt("in_minutes"), LocalDateTime.now())
        val id = Reminders.add(ctx.context, args.string("topic"), time, Reminders.KIND_CHECK_IN)
        return "Check-in #$id planned for ${time.spoken()}."
    }
}

object ListReminders : BleckyTool("list_reminders", "List upcoming reminders and planned check-ins with their ids.") {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        val items = Reminders.list(ctx.context)
        if (items.isEmpty()) return "Nothing scheduled."
        val format = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")
        return items.sortedBy { it.getLong("at") }.joinToString("\n") {
            val at = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it.getLong("at")), ZoneId.systemDefault())
            val kind = if (it.optString("kind") == Reminders.KIND_CHECK_IN) "check-in" else "reminder"
            "#${it.getInt("id")} $kind ${at.format(format)}: ${it.getString("text")}"
        }
    }
}

object CancelReminder : BleckyTool(
    "cancel_reminder", "Cancel an upcoming reminder or check-in by id (use list_reminders first).",
    listOf(Param("id", "integer", "The reminder id")),
) {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        val removed = Reminders.cancel(ctx.context, args.int("id")) ?: throw ToolError("Nothing scheduled with that id.")
        return "Cancelled: ${removed.getString("text")}"
    }
}

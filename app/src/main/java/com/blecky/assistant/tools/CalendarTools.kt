package com.blecky.assistant.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.TimeZone

fun calendarTools(): List<BleckyTool> = listOf(ListEvents, AddEvent)

/** Reads events from the calendars synced to the phone (e.g. Google Calendar). */
object ListEvents : BleckyTool(
    "list_events", "List calendar events for a day or range of days.",
    listOf(
        Param("start_date", "string", "First day, YYYY-MM-DD (default today)", required = false),
        Param("days", "integer", "Number of days to include (default 1)", required = false),
    ),
) {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        ctx.context.requirePermission(Manifest.permission.READ_CALENDAR, "read your calendar")
        val start = args.optString("start_date")?.let {
            try { LocalDate.parse(it) } catch (e: DateTimeParseException) { throw ToolError("Bad date '$it'.") }
        } ?: LocalDate.now()
        val days = (args.optInt("days") ?: 1).coerceIn(1, 31)
        val zone = ZoneId.systemDefault()
        val from = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = start.plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val columns = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        val format = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")
        val events = mutableListOf<String>()
        ctx.context.contentResolver.query(uri, columns, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext() && events.size < 30) {
                val title = c.getString(0) ?: "(no title)"
                val begin = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(c.getLong(1)), zone)
                val whenText = if (c.getInt(2) == 1) begin.format(DateTimeFormatter.ofPattern("EEE d MMM")) + " all day" else begin.format(format)
                val where = c.getString(3)?.takeIf { it.isNotBlank() }?.let { " at $it" } ?: ""
                events += "$whenText: $title$where"
            }
        }
        return if (events.isEmpty()) "No events in that period." else events.joinToString("\n")
    }
}

object AddEvent : BleckyTool(
    "add_event", "Add an event to the user's main calendar.",
    listOf(
        Param("title", "string", "Event title"),
        Param("start", "string", "Local start date-time, ISO format e.g. 2026-10-28T15:00"),
        Param("duration_minutes", "integer", "Length in minutes (default 60)", required = false),
        Param("location", "string", "Optional location", required = false),
    ),
) {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        ctx.context.requirePermission(Manifest.permission.WRITE_CALENDAR, "add calendar events")
        val start = try {
            LocalDateTime.parse(args.string("start").replace(' ', 'T').take(19))
        } catch (e: DateTimeParseException) {
            throw ToolError("I couldn't understand the start time.")
        }
        val minutes = (args.optInt("duration_minutes") ?: 60).coerceIn(5, 24 * 60)
        val zone = ZoneId.systemDefault()
        val begin = start.atZone(zone).toInstant().toEpochMilli()

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, primaryCalendarId(ctx))
            put(CalendarContract.Events.TITLE, args.string("title"))
            put(CalendarContract.Events.DTSTART, begin)
            put(CalendarContract.Events.DTEND, begin + minutes * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            args.optString("location")?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        }
        ctx.context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: throw ToolError("The calendar didn't accept the event.")
        return "Added '${args.string("title")}' on ${start.format(DateTimeFormatter.ofPattern("EEEE d MMMM 'at' HH:mm"))}."
    }

    private fun primaryCalendarId(ctx: ToolContext): Long {
        val columns = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY)
        val writable = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR} AND ${CalendarContract.Calendars.VISIBLE} = 1"
        var fallback: Long? = null
        ctx.context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, columns, writable, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(1) == 1) return c.getLong(0)
                if (fallback == null) fallback = c.getLong(0)
            }
        }
        return fallback ?: throw ToolError("No writable calendar on this phone. Add a Google account first.")
    }
}

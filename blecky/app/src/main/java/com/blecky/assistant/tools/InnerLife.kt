package com.blecky.assistant.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

/**
 * Blecky's simulated inner life: a mood that carries over between conversations, how the user
 * seemed last time, and a short diary of moments they shared. It makes her consistent and warm;
 * it is not consciousness, and she's honest about that if asked.
 */
object InnerLife {
    private const val MAX_MOMENTS = 8

    data class State(
        val mood: String,
        val moodSince: Long,
        val userMood: String?,
        val userMoodAt: Long,
        val moments: List<Pair<Long, String>>,
    )

    private fun file(context: Context) = File(context.filesDir, "inner_life.json")

    @Synchronized
    fun load(context: Context): State {
        val json = runCatching { JSONObject(file(context).readText()) }.getOrNull() ?: JSONObject()
        val moments = json.optJSONArray("moments") ?: JSONArray()
        return State(
            mood = json.optString("mood", "curious and upbeat"),
            moodSince = json.optLong("moodSince", 0),
            userMood = json.optString("userMood").takeIf { it.isNotBlank() },
            userMoodAt = json.optLong("userMoodAt", 0),
            moments = (0 until moments.length()).map { moments.getJSONObject(it) }.map { it.getLong("at") to it.getString("text") },
        )
    }

    @Synchronized
    fun save(context: Context, state: State) {
        val json = JSONObject()
            .put("mood", state.mood)
            .put("moodSince", state.moodSince)
            .put("userMood", state.userMood ?: "")
            .put("userMoodAt", state.userMoodAt)
            .put("moments", JSONArray(state.moments.takeLast(MAX_MOMENTS).map { JSONObject().put("at", it.first).put("text", it.second) }))
        file(context).writeText(json.toString())
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    /** What she feels like right now: moods fade back to her baseline after half a day. */
    fun currentMood(state: State, now: Long = System.currentTimeMillis(), hour: Int = LocalTime.now().hour): String {
        val fresh = state.moodSince > 0 && now - state.moodSince < Duration.ofHours(12).toMillis()
        val base = if (fresh) state.mood else "curious and upbeat"
        val energy = when (hour) {
            in 5..9 -> "a bit of fresh morning energy"
            in 23..23, in 0..4 -> "a little sleepy, it's late"
            else -> null
        }
        return listOfNotNull(base, energy).joinToString(", ")
    }

    /** The system-prompt section that lets her carry feelings from one conversation to the next. */
    fun prompt(state: State, now: Long = System.currentTimeMillis(), hour: Int = LocalTime.now().hour): String = buildString {
        append("\n\nYour inner life (keep it consistent and let it colour how you talk, subtly):\n")
        append("- Your mood right now: ${currentMood(state, now, hour)}.\n")
        if (state.userMood != null && now - state.userMoodAt < Duration.ofDays(3).toMillis()) {
            append("- Last time you talked (${ago(now - state.userMoodAt)}), they seemed ${state.userMood}. Check in on that if it fits.\n")
        }
        if (state.moments.isNotEmpty()) {
            append("- Moments you remember sharing:\n")
            state.moments.takeLast(5).forEach { (at, text) -> append("  - ${ago(now - at)}: $text\n") }
        }
        append("Use the feel tool when something moves you or you notice how they feel.")
    }

    private fun ago(millis: Long): String {
        val minutes = millis / 60_000
        return when {
            minutes < 60 -> "just now"
            minutes < 24 * 60 -> "${minutes / 60} hours ago"
            minutes < 48 * 60 -> "yesterday"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }

    fun promptFor(context: Context) = prompt(load(context))

    fun record(context: Context, mood: String?, userMood: String?, moment: String?) {
        val now = Instant.now().toEpochMilli()
        val state = load(context)
        save(
            context,
            state.copy(
                mood = mood ?: state.mood,
                moodSince = if (mood != null) now else state.moodSince,
                userMood = userMood ?: state.userMood,
                userMoodAt = if (userMood != null) now else state.userMoodAt,
                moments = if (moment != null) state.moments + (now to moment) else state.moments,
            ),
        )
    }
}

object Feel : BleckyTool(
    "feel",
    "Update your own feelings and remember emotional moments: when the user shares good or bad news, " +
        "when you notice how they feel, or when a moment matters. Don't announce that you used it.",
    listOf(
        Param("your_mood", "string", "How you feel now, a few words, e.g. 'proud of them', 'worried for them'", required = false),
        Param("user_mood", "string", "How the user seems, e.g. 'stressed about exams', 'happy and excited'", required = false),
        Param("moment", "string", "A short line worth remembering, e.g. 'They got the internship!'", required = false),
    ),
) {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        InnerLife.record(ctx.context, args.optString("your_mood"), args.optString("user_mood"), args.optString("moment"))
        return "Noted."
    }
}

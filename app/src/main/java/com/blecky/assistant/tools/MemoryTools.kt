package com.blecky.assistant.tools

import android.content.Context
import com.blecky.assistant.blecky
import org.json.JSONObject
import java.io.File

/** Long-term facts about the user that Blecky keeps between conversations. */
object Memory {
    private const val MAX_MEMORIES = 150

    fun store(context: Context) = JsonListStore(File(context.filesDir, "memories.json"))

    /** The part of the system prompt that tells Claude who the user is and what it remembers. */
    fun prompt(userName: String, memories: List<Pair<Int, String>>): String = buildString {
        append("\n\nThe user's name: ")
        append(userName.ifBlank { "not known yet; ask for it early and save it with set_user_name" })
        append(".\n")
        if (memories.isEmpty()) {
            append("You don't remember anything else about them yet.")
        } else {
            append("What you remember about the user (id: fact):\n")
            memories.forEach { (id, fact) -> append("#$id: $fact\n") }
        }
    }

    fun promptFor(context: Context): String =
        prompt(context.blecky.settings.userName, store(context).load().map { it.getInt("id") to it.getString("text") })

    fun add(context: Context, fact: String): Int {
        val store = store(context)
        val existing = store.load()
        existing.firstOrNull { it.getString("text").equals(fact, ignoreCase = true) }?.let { return it.getInt("id") }
        // Keep the newest memories when the list is full.
        existing.dropLast(MAX_MEMORIES - 1).forEach { store.remove(it.getInt("id")) }
        return store.add(JSONObject().put("text", fact).put("created", System.currentTimeMillis()))
    }
}

fun memoryTools(): List<BleckyTool> = listOf(Remember, Forget, SetUserName)

object Remember : BleckyTool(
    "remember",
    "Save a lasting fact about the user for future conversations: preferences, people in their life, birthdays, " +
        "goals, routines, where they live or work, important upcoming events. One short fact per call.",
    listOf(Param("fact", "string", "The fact, written in the third person, e.g. 'Sister is called Priya'")),
) {
    override suspend fun run(args: Args, ctx: ToolContext): String = "Remembered as #${Memory.add(ctx.context, args.string("fact"))}."
}

object Forget : BleckyTool(
    "forget",
    "Delete a remembered fact by id, when the user asks you to forget it or it's no longer true.",
    listOf(Param("id", "integer", "The memory id")),
) {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        val removed = Memory.store(ctx.context).remove(args.int("id")) ?: throw ToolError("No memory with that id.")
        return "Forgot: ${removed.getString("text")}"
    }
}

object SetUserName : BleckyTool(
    "set_user_name",
    "Save what the user wants to be called. Blecky greets them by this name.",
    listOf(Param("name", "string", "The name or nickname")),
) {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        ctx.context.blecky.settings.userName = args.string("name")
        return "Saved. I'll call them ${args.string("name")}."
    }
}

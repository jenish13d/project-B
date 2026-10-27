package com.blecky.assistant.tools

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date

/** A tiny JSON-file list of records, each with a numeric "id". */
open class JsonListStore(private val file: File) {
    @Synchronized
    fun load(): List<JSONObject> {
        if (!file.exists()) return emptyList()
        val array = runCatching { JSONArray(file.readText()) }.getOrElse { JSONArray() }
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    @Synchronized
    fun save(items: List<JSONObject>) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONArray(items).toString())
        tmp.renameTo(file)
    }

    @Synchronized
    fun add(item: JSONObject): Int {
        val items = load()
        val id = (items.maxOfOrNull { it.getInt("id") } ?: 0) + 1
        save(items + item.put("id", id))
        return id
    }

    @Synchronized
    fun remove(id: Int): JSONObject? {
        val items = load()
        val target = items.firstOrNull { it.getInt("id") == id } ?: return null
        save(items - target)
        return target
    }
}

class NotesStore(file: File) : JsonListStore(file)

private fun ToolContext.notes() = NotesStore(File(context.filesDir, "notes.json"))

fun notesTools(): List<BleckyTool> = listOf(AddNote, ListNotes, DeleteNote)

object AddNote : BleckyTool(
    "add_note", "Save a note, to-do item or shopping-list item for the user.",
    listOf(Param("text", "string", "What to write down")),
) {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        val id = ctx.notes().add(JSONObject().put("text", args.string("text")).put("created", System.currentTimeMillis()))
        return "Saved note #$id."
    }
}

object ListNotes : BleckyTool("list_notes", "List all saved notes and to-dos with their ids.") {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        val notes = ctx.notes().load()
        if (notes.isEmpty()) return "No notes saved."
        val format = DateFormat.getDateInstance(DateFormat.MEDIUM)
        return notes.joinToString("\n") {
            "#${it.getInt("id")} (${format.format(Date(it.optLong("created")))}): ${it.getString("text")}"
        }
    }
}

object DeleteNote : BleckyTool(
    "delete_note", "Delete a saved note by its id (use list_notes first to find it).",
    listOf(Param("id", "integer", "The note id")),
) {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        val removed = ctx.notes().remove(args.int("id")) ?: throw ToolError("No note with that id.")
        return "Deleted note: ${removed.getString("text")}"
    }
}

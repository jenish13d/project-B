package com.blecky.assistant.tools

import android.content.Context
import android.content.pm.PackageManager

/** How a tool asks the user to approve something (by voice or on screen). */
interface UserChannel {
    suspend fun confirm(question: String): Boolean
}

class ToolContext(val context: Context, val user: UserChannel)

/** Thrown by a tool to report a failure Claude should explain to the user. */
class ToolError(message: String) : Exception(message)

data class Param(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true,
    val enum: List<String>? = null,
) {
    fun schema(): Map<String, Any> = buildMap {
        put("type", type)
        put("description", description)
        enum?.let { put("enum", it) }
    }
}

abstract class BleckyTool(
    val name: String,
    val description: String,
    val params: List<Param> = emptyList(),
) {
    abstract suspend fun run(args: Args, ctx: ToolContext): String
}

class Args(private val values: Map<String, Any?>) {
    fun optString(name: String): String? =
        (values[name] as? String)?.trim()?.takeIf { it.isNotEmpty() }

    fun string(name: String): String = optString(name) ?: throw ToolError("Missing '$name'.")

    fun optInt(name: String): Int? = when (val v = values[name]) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    fun int(name: String): Int = optInt(name) ?: throw ToolError("Missing number '$name'.")

    fun optBool(name: String): Boolean? = when (val v = values[name]) {
        is Boolean -> v
        is String -> v.trim().lowercase().toBooleanStrictOrNull()
        else -> null
    }
}

fun Context.requirePermission(permission: String, what: String) {
    if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
        throw ToolError("Blecky doesn't have permission to $what. Open the Blecky app and tap step 1 to allow it.")
    }
}

fun allTools(): List<BleckyTool> =
    phoneTools() + memoryTools() + notesTools() + reminderTools() + calendarTools() + messageTools()

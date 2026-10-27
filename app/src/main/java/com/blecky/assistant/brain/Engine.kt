package com.blecky.assistant.brain

import com.blecky.assistant.tools.Args
import com.blecky.assistant.tools.BleckyTool
import com.blecky.assistant.tools.ToolContext
import com.blecky.assistant.tools.ToolError

/** One AI provider's conversation loop. Calls are serialized by [com.blecky.assistant.Brain]. */
interface Engine {
    /** Answers [text] (already stamped by [Persona.userMessage]); never throws, returns a spoken reply. */
    suspend fun ask(text: String, key: String, ctx: ToolContext, fromScheduler: Boolean): String

    fun reset()
}

class ToolOutcome(val text: String, val isError: Boolean)

/** Runs one of Blecky's tools and turns any failure into a message the AI can explain. */
suspend fun executeTool(tool: BleckyTool?, name: String, input: Map<String, Any?>, ctx: ToolContext): ToolOutcome {
    if (tool == null) return ToolOutcome("Unknown tool $name", isError = true)
    return try {
        ToolOutcome(tool.run(Args(input), ctx), isError = false)
    } catch (e: ToolError) {
        ToolOutcome(e.message ?: "Failed", isError = true)
    } catch (e: SecurityException) {
        ToolOutcome("Android blocked this: ${e.message}", isError = true)
    } catch (e: Exception) {
        ToolOutcome("Tool failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
    }
}

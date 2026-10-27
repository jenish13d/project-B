package com.blecky.assistant

import android.content.Context
import com.blecky.assistant.brain.ClaudeEngine
import com.blecky.assistant.brain.Engine
import com.blecky.assistant.brain.FreeProvider
import com.blecky.assistant.brain.OpenAiEngine
import com.blecky.assistant.brain.Persona
import com.blecky.assistant.tools.BleckyTool
import com.blecky.assistant.tools.ToolContext
import com.blecky.assistant.tools.UserChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Sends what the user said to the chosen AI (free Gemini or Groq, or paid Claude) and returns her reply. */
class Brain(
    private val context: Context,
    private val settings: Settings,
    tools: List<BleckyTool>,
) {
    private val engines: Map<String, Engine> = mapOf(
        Settings.PROVIDER_GEMINI to OpenAiEngine(context, settings, tools, FreeProvider.GEMINI),
        Settings.PROVIDER_GROQ to OpenAiEngine(context, settings, tools, FreeProvider.GROQ),
        Settings.PROVIDER_CLAUDE to ClaudeEngine(context, settings, tools),
    )
    private val lock = Mutex()
    private var lastProvider: String? = null

    fun reset() = engines.values.forEach { it.reset() }

    /**
     * Answers [text] from the user. With [fromScheduler], [text] is an instruction from Blecky's
     * own scheduler (a check-in), and Blecky opens the conversation instead.
     */
    suspend fun ask(text: String, user: UserChannel, fromScheduler: Boolean = false): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val provider = settings.provider
            val key = settings.keyFor(provider)
            if (key.isBlank()) return@withLock missingKey(provider)
            if (provider != lastProvider) {
                reset()
                lastProvider = provider
            }
            val engine = engines[provider] ?: engines.getValue(Settings.PROVIDER_GEMINI)
            engine.ask(Persona.userMessage(text, fromScheduler), key, ToolContext(context, user), fromScheduler)
        }
    }

    private fun missingKey(provider: String) = when (provider) {
        Settings.PROVIDER_CLAUDE -> "I need an Anthropic API key first. Open Settings and paste it in."
        Settings.PROVIDER_GROQ -> "I need a free Groq key first. Get one at ${FreeProvider.GROQ.keyUrl} and paste it in Settings."
        else -> "I need a free Gemini key first. Get one at ${FreeProvider.GEMINI.keyUrl} and paste it in Settings."
    }
}

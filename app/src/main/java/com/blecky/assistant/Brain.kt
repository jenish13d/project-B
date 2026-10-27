package com.blecky.assistant

import android.content.Context
import com.blecky.assistant.brain.ClaudeEngine
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

/**
 * Sends what the user said to Blecky's brain and returns her reply. By default the brain combines
 * every free AI the user has a key for; Claude is there for anyone with Anthropic credit.
 */
class Brain(
    private val context: Context,
    private val settings: Settings,
    tools: List<BleckyTool>,
) {
    private val free = OpenAiEngine(context, settings, tools) { freeProviders() }
    private val claude = ClaudeEngine(context, settings, tools)
    private val lock = Mutex()
    private var lastProvider: String? = null

    fun reset() {
        free.reset()
        claude.reset()
    }

    /** Free providers in use, with keys: all of them in auto mode, or just the chosen one. */
    private fun freeProviders(): List<Pair<FreeProvider, String>> {
        val chosen = settings.provider
        val candidates = if (chosen == Settings.PROVIDER_AUTO) FreeProvider.ALL else listOfNotNull(FreeProvider.byId(chosen))
        return candidates.map { it to settings.keyFor(it.id) }.filter { it.second.isNotBlank() }
    }

    /**
     * Answers [text] from the user. With [fromScheduler], [text] is an instruction from Blecky's
     * own scheduler (a check-in), and Blecky opens the conversation instead.
     */
    suspend fun ask(text: String, user: UserChannel, fromScheduler: Boolean = false): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val provider = settings.provider
            if (provider != lastProvider) {
                reset()
                lastProvider = provider
            }
            val message = Persona.userMessage(text, fromScheduler)
            val ctx = ToolContext(context, user)
            if (provider == Settings.PROVIDER_CLAUDE) {
                if (settings.apiKey.isBlank()) return@withLock "I need an Anthropic API key for Claude. Or switch my brain to Auto in Settings; that one's free."
                claude.ask(message, settings.apiKey, ctx, fromScheduler)
            } else {
                if (freeProviders().isEmpty()) {
                    return@withLock "I need a free AI key first. Gemini is easiest: get one at ${FreeProvider.GEMINI.keyUrl} and paste it in Settings."
                }
                free.ask(message, "", ctx, fromScheduler)
            }
        }
    }
}

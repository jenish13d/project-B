package com.blecky.assistant

import android.content.Context
import android.util.Log
import com.blecky.assistant.brain.ClaudeEngine
import com.blecky.assistant.brain.FreeProvider
import com.blecky.assistant.brain.LocalCommands
import com.blecky.assistant.brain.OfflineEngine
import com.blecky.assistant.brain.OpenAiEngine
import com.blecky.assistant.brain.Persona
import com.blecky.assistant.brain.WebLookup
import com.blecky.assistant.tools.BleckyTool
import com.blecky.assistant.tools.ToolContext
import com.blecky.assistant.tools.UserChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Sends what the user said to Blecky's brain and returns her reply. In Auto mode it combines every
 * free online AI the user has a key for, then falls back to the AI running on the phone (no
 * internet, no key), and finally to built-in commands. Claude is there for anyone with credit.
 */
class Brain(
    private val context: Context,
    private val settings: Settings,
    tools: List<BleckyTool>,
) {
    private val free = OpenAiEngine(context, settings, tools) { freeProviders() }
    val offline = OfflineEngine(context, settings, tools)
    private val claude = ClaudeEngine(context, settings, tools)
    private val lock = Mutex()
    private var lastProvider: String? = null

    fun reset() {
        free.reset()
        offline.reset()
        claude.reset()
    }

    /** Free providers in use, with keys: all of them in auto mode, or just the chosen one. */
    private fun freeProviders(): List<Pair<FreeProvider, String>> {
        val chosen = settings.provider
        val candidates = if (chosen == Settings.PROVIDER_AUTO) FreeProvider.ALL else listOfNotNull(FreeProvider.byId(chosen))
        return candidates.map { it to settings.keyFor(it.id) }.filter { it.second.isNotBlank() }
    }

    private fun online() = WebLookup.isOnline(context)

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
            try {
                when (provider) {
                    Settings.PROVIDER_CLAUDE ->
                        if (settings.apiKey.isBlank()) "I need an Anthropic API key for Claude. Or switch my brain to Auto in Settings; that one's free."
                        else claude.ask(message, settings.apiKey, ctx, fromScheduler)
                    Settings.PROVIDER_OFFLINE -> offline.ask(message, "", ctx, fromScheduler)
                    else -> askFree(provider, text, message, ctx, fromScheduler)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never let one bad answer crash the whole app.
                Log.e("Blecky", "Brain failed", e)
                "Oops, something glitched in my head (${e.javaClass.simpleName}). Try that again?"
            }
        }
    }

    private suspend fun askFree(provider: String, text: String, message: String, ctx: ToolContext, fromScheduler: Boolean): String {
        val auto = provider == Settings.PROVIDER_AUTO
        var failure: String? = null
        if (freeProviders().isNotEmpty() && online()) {
            val reply = free.ask(message, "", ctx, fromScheduler)
            if (!free.lastTurnFailed || !auto) return reply
            failure = reply
        }
        // Offline, out of free limits, or no keys: the on-phone brain takes over.
        if (auto && offline.available()) return offline.ask(message, "", ctx, fromScheduler)
        if (!fromScheduler) LocalCommands.parse(text)?.let { return offline.runCommand(it, ctx).text }
        return failure ?: when {
            freeProviders().isEmpty() ->
                "I need a brain first. Add a free AI key in Settings (Gemini is easiest: ${FreeProvider.GEMINI.keyUrl}), " +
                    "or download an on-phone AI that works without internet."
            else -> "I'm offline right now. Download an on-phone AI in Settings and I can still chat without internet."
        }
    }
}

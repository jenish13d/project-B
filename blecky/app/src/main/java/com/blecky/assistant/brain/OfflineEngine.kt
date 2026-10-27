package com.blecky.assistant.brain

import android.content.Context
import android.util.Log
import com.blecky.assistant.Settings
import com.blecky.assistant.tools.BleckyTool
import com.blecky.assistant.tools.InnerLife
import com.blecky.assistant.tools.Memory
import com.blecky.assistant.tools.ToolContext
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.tool
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import com.google.ai.edge.litertlm.Engine as LiteRtEngine

/**
 * Blecky's on-phone brain: everyday commands are handled instantly by [LocalCommands], and
 * everything else goes to a small AI model running on the phone. No internet, no key.
 */
class OfflineEngine(
    private val context: Context,
    private val settings: Settings,
    tools: List<BleckyTool>,
) : Engine {
    val models = OfflineModels(context)
    private val toolsByName = tools.associateBy { it.name }

    // Small models get a short list of tools: every tool description costs time on a phone CPU.
    private val offlineTools = OFFLINE_TOOLS.mapNotNull { toolsByName[it] }

    private var engine: LiteRtEngine? = null
    private var loadedModel: String? = null
    private var conversation: Conversation? = null
    private var lastTurnAt = 0L

    /** Tools run with whichever channel (voice or screen) the current turn came from. */
    @Volatile
    private var currentCtx: ToolContext? = null

    /** What the last tool said, used when the model answers a tool call with no words. */
    @Volatile
    private var lastToolResult: String? = null

    override var lastTurnFailed = false
        private set

    fun available() = models.active(settings.offlineModel) != null

    override fun reset() {
        conversation?.close()
        conversation = null
    }

    /** Frees the model's memory (about 1.5 GB for Gemma), after it failed or sat unused. */
    @Synchronized
    private fun unload() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
        loadedModel = null
    }

    // Keeping the model loaded between chats squeezes the phone's memory and makes everything lag,
    // so it's released after a few idle minutes. Reloading takes a few seconds.
    private val idle = Handler(Looper.getMainLooper())
    private val unloadWhenIdle = Runnable { thread(name = "blecky-unload") { if (!busy) unload() } }

    @Volatile
    private var busy = false

    override suspend fun ask(text: String, key: String, ctx: ToolContext, fromScheduler: Boolean): String {
        busy = true
        idle.removeCallbacks(unloadWhenIdle)
        try {
            return answer(text, ctx, fromScheduler)
        } finally {
            busy = false
            idle.postDelayed(unloadWhenIdle, IDLE_UNLOAD_MS)
        }
    }

    private suspend fun answer(text: String, ctx: ToolContext, fromScheduler: Boolean): String {
        lastTurnFailed = false
        val words = Persona.unstamp(text)
        val model = models.active(settings.offlineModel)
        if (!fromScheduler) {
            LocalCommands.parse(words)?.let { command ->
                val outcome = runCommand(command, ctx)
                // If no contact or app matched, it may not have been a command at all; let the AI try.
                if (!outcome.isError || model == null) return outcome.text
            }
        }

        if (model == null) {
            lastTurnFailed = true
            return "I don't have an on-phone brain yet. Download one in Settings, or add a free AI key."
        }
        // Research step: with internet, fetch fresh facts the small model can't know.
        val facts = if (!fromScheduler && WebLookup.isOnline(context)) WebLookup.lookup(words, ctx) else null
        // Small models parrot a time stamp back, so send just the words.
        val prompt = when {
            fromScheduler -> text
            facts != null -> "$words\n\n(Fresh from the internet. Use it to answer in a sentence or two; " +
                "don't read it all out or mention searching unless asked:\n$facts)"
            else -> words
        }
        // Several downloaded models act as backups for each other.
        val candidates = listOf(model) + models.installed().filter { it.id != model.id }.sortedByDescending { it.sizeMb }
        for (candidate in candidates.take(2)) {
            try {
                currentCtx = ctx
                lastToolResult = null
                // Under the same lock as unload(), so an idle release can't free the model mid-answer.
                val reply = synchronized(this) { conversationFor(candidate).sendMessage(prompt) }
                return reply.contents.contents.filterIsInstance<Content.Text>().joinToString(" ") { it.text }
                    .replace(Regex("(?s)<think>.*?</think>"), "")
                    .trim()
                    .ifEmpty { lastToolResult ?: Persona.done() }
            } catch (e: Exception) {
                Log.e("Blecky", "On-phone model ${candidate.id} failed", e)
                reset()
                unload()
            }
        }
        lastTurnFailed = true
        return "My on-phone brain tripped up. Try again, or pick a smaller model in Settings."
    }

    /** Runs a recognised everyday command; its result is the reply. */
    suspend fun runCommand(command: LocalCommands.Command, ctx: ToolContext): ToolOutcome {
        if (command.tool == "time") {
            return ToolOutcome("It's ${LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a"))}.", isError = false)
        }
        return executeTool(toolsByName[command.tool], command.tool, command.args, ctx)
    }

    @Synchronized
    private fun conversationFor(model: OfflineModels.Model): Conversation {
        val now = System.currentTimeMillis()
        if (now - lastTurnAt > Persona.IDLE_RESET_MS) reset()
        lastTurnAt = now
        conversation?.takeIf { it.isAlive && loadedModel == model.id }?.let { return it }

        if (loadedModel != model.id) {
            conversation?.close()
            engine?.close()
            val config = EngineConfig(
                modelPath = models.path(model).absolutePath,
                backend = Backend.CPU(),
                maxNumTokens = 2048,
                cacheDir = context.cacheDir.absolutePath,
            )
            engine = LiteRtEngine(config).also { it.initialize() }
            loadedModel = model.id
        }
        val tools = if (model.canUseTools) offlineTools.map { tool(Adapter(it)) } else emptyList()
        return engine!!.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(systemPrompt()),
                tools = tools,
                samplerConfig = SamplerConfig(40, 0.95, 0.8, 0),
                automaticToolCalling = true,
                maxOutputToken = 256,
                thinkingConfig = ThinkingConfig(false),
            ),
        ).also { conversation = it }
    }

    /** A short prompt: on a phone CPU every word of it costs time before she can answer. */
    private fun systemPrompt(): String {
        val memories = Memory.store(context).load().takeLast(8).joinToString("; ") { it.getString("text") }
        val name = settings.userName.ifBlank { "unknown" }
        return """
            You are Blecky, the user's warm, witty AI friend, like JARVIS or FRIDAY, running on their phone.
            Today is ${java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy"))}.
            Talk like a close friend: one or two short spoken sentences, no lists, emojis or markdown.
            Answer in the language and script of the user's most recent message only: Hindi in Devanagari,
            Gujarati in Gujarati script, English in English. Don't repeat yourself.
            Never claim you did something unless a tool confirmed it.
            User's name: $name. What you know about them: ${memories.ifBlank { "nothing yet" }}.
            Your mood: ${InnerLife.currentMood(InnerLife.load(context))}.
        """.trimIndent()
    }

    private inner class Adapter(private val tool: BleckyTool) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String =
            OpenAiEngine.toolJson(tool).getJSONObject("function").put("name", offlineName(tool.name)).toString()

        override fun execute(paramsJsonString: String): String = runBlocking {
            val ctx = currentCtx ?: return@runBlocking "Error: not available right now"
            val input = runCatching { OpenAiEngine.jsonToMap(org.json.JSONObject(paramsJsonString)) }.getOrDefault(emptyMap())
            executeTool(tool, tool.name, input, ctx).text.also { lastToolResult = it }
        }
    }

    companion object {
        private const val IDLE_UNLOAD_MS = 3 * 60 * 1000L

        /**
         * "call" is a keyword in Gemma's tool-call format and breaks its parser (seen in testing),
         * so tools get names that can't clash.
         */
        internal fun offlineName(name: String) = if (name == "call") "make_call" else name

        private val OFFLINE_TOOLS = listOf(
            "call", "send_text", "open_app", "set_timer", "set_alarm", "set_reminder", "flashlight", "add_note", "remember",
        )
    }
}

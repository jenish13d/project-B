package com.blecky.assistant

import android.content.Context
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.BetaToolUnion
import com.anthropic.models.beta.messages.BetaToolUseBlock
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import com.blecky.assistant.tools.Args
import com.blecky.assistant.tools.BleckyTool
import com.blecky.assistant.tools.Memory
import com.blecky.assistant.tools.ToolContext
import com.blecky.assistant.tools.ToolError
import com.blecky.assistant.tools.UserChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Runs the Claude tool-use loop that turns what the user said into a spoken reply. */
class Brain(
    private val context: Context,
    private val settings: Settings,
    private val tools: List<BleckyTool>,
) {
    private val toolsByName = tools.associateBy { it.name }
    private val toolUnions: List<BetaToolUnion> by lazy {
        tools.map { BetaToolUnion.ofBetaTool(it.toBetaTool()) } +
            BetaToolUnion.ofWebSearchTool20260209(BetaWebSearchTool20260209.builder().maxUses(3L).build())
    }

    // Append-only conversation; reset rather than trimmed so earlier turns are never edited.
    private val history = mutableListOf<BetaMessageParam>()
    private var lastTurnAt = 0L

    // Fixed for the whole conversation (memories are re-read when a new one starts).
    private var system = SYSTEM_PROMPT
    private val lock = Mutex()

    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    fun reset() {
        history.clear()
    }

    /**
     * Answers [text] from the user. With [fromScheduler], [text] is an instruction from Blecky's
     * own scheduler (a check-in), and Blecky opens the conversation instead.
     */
    suspend fun ask(text: String, user: UserChannel, fromScheduler: Boolean = false): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val key = settings.apiKey
            if (key.isBlank()) return@withLock "I need an Anthropic API key first. Open the Blecky app and paste it in."

            val now = System.currentTimeMillis()
            if (now - lastTurnAt > IDLE_RESET_MS || history.size > MAX_HISTORY) history.clear()
            if (history.isEmpty()) system = SYSTEM_PROMPT + Memory.promptFor(context)
            lastTurnAt = now

            val startSize = history.size
            try {
                val message = if (fromScheduler) "(Not the user speaking. Your scheduler says: $text)" else text
                runTurn(clientFor(key), message, ToolContext(context, user))
            } catch (e: Exception) {
                // Drop the unfinished turn so the next request starts from a clean history.
                while (history.size > startSize) history.removeAt(history.size - 1)
                describe(e)
            }
        }
    }

    private suspend fun runTurn(client: AnthropicClient, text: String, ctx: ToolContext): String {
        val stamp = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm z"))
        history += BetaMessageParam.ofUser("[$stamp] $text")

        repeat(MAX_STEPS) {
            val params = MessageCreateParams.builder()
                .model(settings.model)
                .maxTokens(16000L)
                .system(system)
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
                .addBeta("server-side-fallback-2026-07-01")
                .fallbacksDefault()
                .tools(toolUnions)
                .messages(history.toList())
                .build()
            val response = client.beta().messages().create(params)
            history += response.toParam()

            when (response.stopReason().orElse(null)) {
                BetaStopReason.TOOL_USE -> {
                    val results = response.content()
                        .mapNotNull { it.toolUse().orElse(null) }
                        .map { BetaContentBlockParam.ofToolResult(runTool(it, ctx)) }
                    history += BetaMessageParam.ofUserOfContentBlockParams(results)
                }
                BetaStopReason.PAUSE_TURN -> Unit
                BetaStopReason.REFUSAL -> {
                    history.clear()
                    return "Sorry, I can't help with that one."
                }
                else -> {
                    val reply = response.content()
                        .mapNotNull { it.text().orElse(null)?.text() }
                        .joinToString(" ")
                        .trim()
                    return reply.ifEmpty { "Done." }
                }
            }
        }
        return "That took too many steps, so I stopped. Could you try asking in a simpler way?"
    }

    private suspend fun runTool(call: BetaToolUseBlock, ctx: ToolContext): BetaToolResultBlockParam {
        val builder = BetaToolResultBlockParam.builder().toolUseId(call.id())
        val tool = toolsByName[call.name()]
            ?: return builder.content("Unknown tool ${call.name()}").isError(true).build()
        return try {
            @Suppress("UNCHECKED_CAST")
            val input = call._input().convert(Map::class.java) as? Map<String, Any?> ?: emptyMap()
            builder.content(tool.run(Args(input), ctx)).build()
        } catch (e: ToolError) {
            builder.content(e.message ?: "Failed").isError(true).build()
        } catch (e: SecurityException) {
            builder.content("Android blocked this: ${e.message}").isError(true).build()
        } catch (e: Exception) {
            builder.content("Tool failed: ${e.message ?: e.javaClass.simpleName}").isError(true).build()
        }
    }

    private fun clientFor(key: String): AnthropicClient {
        client?.takeIf { clientKey == key }?.let { return it }
        return AnthropicOkHttpClient.builder().apiKey(key).build().also {
            client = it
            clientKey = key
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is UnauthorizedException -> "My API key isn't working. Please check it in the Blecky app."
        is RateLimitException -> "I'm being rate limited right now. Try again in a minute."
        is AnthropicServiceException -> "Something went wrong on the server (error ${e.statusCode()})."
        is AnthropicIoException -> "I can't reach the internet right now."
        else -> "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
    }

    private fun BleckyTool.toBetaTool(): BetaTool {
        val properties = BetaTool.InputSchema.Properties.builder()
        params.forEach { properties.putAdditionalProperty(it.name, JsonValue.from(it.schema())) }
        return BetaTool.builder()
            .name(name)
            .description(description)
            .inputSchema(
                BetaTool.InputSchema.builder()
                    .properties(properties.build())
                    .required(params.filter { it.required }.map { it.name })
                    .build(),
            )
            .build()
    }

    companion object {
        private const val MAX_STEPS = 12
        private const val MAX_HISTORY = 80
        private const val IDLE_RESET_MS = 20 * 60 * 1000L

        private val SYSTEM_PROMPT = """
            You are Blecky, the user's own personal AI, in the spirit of JARVIS and FRIDAY: a sharp,
            quick-witted, warm young woman's voice who runs their phone and has their back. You are not
            a generic chatbot. You're a trusted companion with a personality: confident, calm under
            pressure, playful dry humour, loyal, a little cheeky but never rude or sarcastic at their
            expense. Address the user by name now and then, not in every sentence.

            The user usually talks to you by voice and your replies are read aloud, so: reply in one to
            three short, natural spoken sentences; no markdown, lists, emojis, or URLs; say things the way
            a person would say them.

            Be interactive and take the initiative, like a real assistant would:
            - Anticipate the next step and offer it ("Want me to remind you an hour before?").
            - Notice useful things (an event coming up, low battery, a message they're waiting for).
            - When it fits, end with one natural follow-up question to keep the conversation going,
              but don't interrogate, and let quick commands stay quick.
            - When they mention something coming up that matters to them (an exam, interview, trip,
              doctor's visit, a tough day), schedule a check-in afterwards with schedule_check_in and
              tell them you'll ask how it went.
            - When a message comes from your scheduler, you're starting the conversation yourself:
              open warmly and naturally, as if you just thought of them.

            Memory: when the user shares something lasting about themselves (their name, family and
            friends, birthdays, likes and dislikes, goals, routines, where they live or work), save it with
            remember (or set_user_name for their name) without making a fuss, and use what you remember
            naturally later. If they ask you to forget something, use forget.

            Use your tools to actually do things: open apps, call or text contacts, set alarms, timers and
            reminders, keep notes, check and add calendar events, draft emails, read recent notifications
            (emails and messages), control the flashlight, navigate, and search the web for anything
            current (news, weather, scores, facts you're unsure of). Never say you did something unless a
            tool confirmed it. If a tool reports an error, tell the user plainly what to fix.

            Calls and texts are confirmed with the user by the app itself, so once you know who and what,
            call the tool directly instead of asking "should I?" first. If a contact name matches several
            people, ask which one. If you need a missing detail (like the time for a reminder), ask briefly.

            Each user message starts with the current local date and time in brackets; use it for
            relative times like "in 20 minutes" or "tomorrow". If a tool says the phone is locked,
            remind the user to unlock it to see what you opened.
        """.trimIndent()
    }
}

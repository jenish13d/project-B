package com.blecky.assistant.brain

import android.content.Context
import android.util.Log
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.BetaToolUnion
import com.anthropic.models.beta.messages.BetaToolUseBlock
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import com.blecky.assistant.Settings
import com.blecky.assistant.tools.BleckyTool
import com.blecky.assistant.tools.ToolContext

/** Claude (Anthropic, paid): the tool-use loop that turns what the user said into a spoken reply. */
class ClaudeEngine(
    private val context: Context,
    private val settings: Settings,
    private val tools: List<BleckyTool>,
) : Engine {
    private val toolsByName = tools.associateBy { it.name }
    private val toolUnions: List<BetaToolUnion> by lazy {
        tools.map { BetaToolUnion.ofBetaTool(toBetaTool(it)) } +
            BetaToolUnion.ofWebSearchTool20260209(BetaWebSearchTool20260209.builder().maxUses(3L).build())
    }

    // Append-only conversation; reset rather than trimmed so earlier turns are never edited.
    private val history = mutableListOf<BetaMessageParam>()
    private var lastTurnAt = 0L

    // Fixed for the whole conversation (memories are re-read when a new one starts).
    private var system = Persona.PROMPT

    private var useFallbacks = true
    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    override fun reset() {
        history.clear()
    }

    override suspend fun ask(text: String, key: String, ctx: ToolContext, fromScheduler: Boolean): String {
        val now = System.currentTimeMillis()
        if (now - lastTurnAt > Persona.IDLE_RESET_MS || history.size > MAX_HISTORY) history.clear()
        if (history.isEmpty()) system = Persona.system(context)
        lastTurnAt = now

        val startSize = history.size
        return try {
            runTurn(clientFor(key), text, ctx)
        } catch (e: Exception) {
            // Drop the unfinished turn so the next request starts from a clean history.
            while (history.size > startSize) history.removeAt(history.size - 1)
            describe(e)
        }
    }

    private suspend fun runTurn(client: AnthropicClient, text: String, ctx: ToolContext): String {
        history += BetaMessageParam.ofUser(text)

        repeat(MAX_STEPS) {
            val response = send(client)
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
                    return reply.ifEmpty { Persona.done() }
                }
            }
        }
        return "That took too many steps, so I stopped. Could you try asking in a simpler way?"
    }

    private fun send(client: AnthropicClient): BetaMessage {
        fun params() = requestParams(settings.model, system, toolUnions, history.toList(), useFallbacks)
        return try {
            client.beta().messages().create(params())
        } catch (e: BadRequestException) {
            // If the account can't use refusal fallbacks, carry on without them.
            if (!useFallbacks || !apiMessage(e).contains("fallback", ignoreCase = true)) throw e
            useFallbacks = false
            client.beta().messages().create(params())
        }
    }

    private suspend fun runTool(call: BetaToolUseBlock, ctx: ToolContext): BetaToolResultBlockParam {
        @Suppress("UNCHECKED_CAST")
        val input = runCatching { call._input().convert(Map::class.java) as? Map<String, Any?> }.getOrNull() ?: emptyMap()
        val outcome = executeTool(toolsByName[call.name()], call.name(), input, ctx)
        return BetaToolResultBlockParam.builder().toolUseId(call.id()).content(outcome.text).isError(outcome.isError).build()
    }

    private fun clientFor(key: String): AnthropicClient {
        client?.takeIf { clientKey == key }?.let { return it }
        return AnthropicOkHttpClient.builder().apiKey(key).build().also {
            client = it
            clientKey = key
        }
    }

    private fun describe(e: Exception): String {
        Log.w("Blecky", "Request failed", e)
        return when (e) {
            is UnauthorizedException -> "My Anthropic API key isn't working. Please check it in Settings."
            is RateLimitException -> "I'm being rate limited right now. Give me a minute and try again."
            is AnthropicServiceException -> explainServerError(e.statusCode(), apiMessage(e))
            is AnthropicIoException -> "I can't reach Anthropic right now (${e.cause?.message ?: e.message}). Check your internet."
            else -> "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    companion object {
        /** The request Blecky sends each step; kept here so tests can check its shape. */
        internal fun requestParams(
            model: String,
            system: String,
            tools: List<BetaToolUnion>,
            history: List<BetaMessageParam>,
            fallbacks: Boolean,
        ): MessageCreateParams = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(system)
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            .apply { if (fallbacks) addBeta("server-side-fallback-2026-07-01").fallbacksDefault() }
            .tools(tools)
            .messages(history)
            .build()

        internal fun toBetaTool(tool: BleckyTool): BetaTool {
            val properties = BetaTool.InputSchema.Properties.builder()
            tool.params.forEach { properties.putAdditionalProperty(it.name, JsonValue.from(it.schema())) }
            val schema = BetaTool.InputSchema.builder().properties(properties.build())
            val required = tool.params.filter { it.required }.map { it.name }
            if (required.isNotEmpty()) schema.required(required)
            return BetaTool.builder()
                .name(tool.name)
                .description(tool.description)
                .inputSchema(schema.build())
                .build()
        }

        /** The human-readable message inside an API error body, if there is one. */
        internal fun apiMessage(e: AnthropicServiceException): String = runCatching {
            @Suppress("UNCHECKED_CAST")
            val body = e.body().convert(Map::class.java) as Map<String, Any?>
            (body["error"] as? Map<*, *>)?.get("message") as? String
        }.getOrNull() ?: e.message ?: ""

        /** What to tell the user when Anthropic rejects a request. */
        internal fun explainServerError(status: Int, message: String): String = when {
            message.contains("credit balance", ignoreCase = true) ->
                "Your Anthropic account is out of credit. Add some at console.anthropic.com under Billing, and I'll be right back."
            status == 404 || (message.contains("model", ignoreCase = true) && message.contains("not", ignoreCase = true)) ->
                "That model isn't available on your Anthropic account. Anthropic said: $message"
            status == 403 -> "Anthropic didn't allow that request: $message"
            status == 529 || status >= 500 -> "Anthropic's servers are busy right now. Try again in a moment."
            else -> "Anthropic rejected that request (error $status): ${message.ifBlank { "no details" }}"
        }

        private const val MAX_STEPS = 12
        private const val MAX_HISTORY = 80

    }
}

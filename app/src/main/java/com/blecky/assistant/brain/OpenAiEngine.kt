package com.blecky.assistant.brain

import android.content.Context
import android.util.Log
import com.blecky.assistant.Settings
import com.blecky.assistant.tools.BleckyTool
import com.blecky.assistant.tools.ToolContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A free AI provider that speaks the OpenAI chat-completions format. */
class FreeProvider(
    val id: String,
    val label: String,
    val baseUrl: String,
    val keyUrl: String,
    /** Tried in order; if none exists any more, one is picked from the provider's model list. */
    val preferredModels: List<String>,
) {
    companion object {
        val GEMINI = FreeProvider(
            Settings.PROVIDER_GEMINI, "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
            "aistudio.google.com/apikey", listOf("gemini-flash-latest", "gemini-3.8-flash", "gemini-2.5-flash"),
        )
        val GROQ = FreeProvider(
            Settings.PROVIDER_GROQ, "Groq", "https://api.groq.com/openai/v1",
            "console.groq.com/keys", listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile", "openai/gpt-oss-20b"),
        )
        val CEREBRAS = FreeProvider(
            Settings.PROVIDER_CEREBRAS, "Cerebras", "https://api.cerebras.ai/v1",
            "cloud.cerebras.ai", listOf("gpt-oss-120b", "qwen-3.8-27b"),
        )
        val OPENROUTER = FreeProvider(
            Settings.PROVIDER_OPENROUTER, "OpenRouter", "https://openrouter.ai/api/v1",
            "openrouter.ai/keys", listOf("google/gemma-4-31b-it:free", "nvidia/nemotron-3-ultra-550b-a55b:free"),
        )

        /** Order the combined brain tries them in: the most generous free tier first. */
        val ALL = listOf(GEMINI, GROQ, CEREBRAS, OPENROUTER)

        fun byId(id: String) = ALL.firstOrNull { it.id == id }
    }
}

/**
 * Runs Blecky's tool loop on free AI providers through their OpenAI-compatible APIs. Given several
 * providers it acts as one brain: when one hits its free limit or fails, the next takes over
 * mid-conversation.
 */
class OpenAiEngine(
    private val context: Context,
    private val settings: Settings,
    private val tools: List<BleckyTool>,
    /** The providers to use (with their keys), in order. */
    private val providers: () -> List<Pair<FreeProvider, String>>,
) : Engine {
    private class ApiError(val provider: FreeProvider, val status: Int, override val message: String) : IOException(message)

    private val toolsByName = tools.associateBy { it.name }
    private val toolsJson: JSONArray by lazy { JSONArray(tools.map(::toolJson)) }
    private val history = mutableListOf<JSONObject>()
    private var system = Persona.PROMPT
    private var lastTurnAt = 0L

    /** The provider that answered last; tried first next time. */
    private var preferred: String? = null

    override fun reset() {
        history.clear()
    }

    override suspend fun ask(text: String, key: String, ctx: ToolContext, fromScheduler: Boolean): String {
        val now = System.currentTimeMillis()
        if (now - lastTurnAt > Persona.IDLE_RESET_MS) history.clear()
        if (history.isEmpty()) system = Persona.system(context)
        trimHistory(history)
        lastTurnAt = now

        val startSize = history.size
        return try {
            history += JSONObject().put("role", "user").put("content", text)
            runTurn(ctx)
        } catch (e: Exception) {
            while (history.size > startSize) history.removeAt(history.size - 1)
            Log.w("Blecky", "Free brain failed", e)
            describe(e)
        }
    }

    private suspend fun runTurn(ctx: ToolContext): String {
        repeat(MAX_STEPS) {
            val (provider, message) = complete()
            message.put("role", "assistant").put(ORIGIN, provider.id)
            history += message
            val calls = message.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                return message.optString("content").takeIf { it.isNotBlank() && it != "null" }?.trim() ?: Persona.done()
            }
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val function = call.getJSONObject("function")
                val name = function.optString("name")
                val input = runCatching { jsonToMap(JSONObject(function.optString("arguments").ifBlank { "{}" })) }.getOrDefault(emptyMap())
                val outcome = executeTool(toolsByName[name], name, input, ctx)
                history += JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", call.optString("id"))
                    .put("name", name)
                    .put("content", if (outcome.isError) "Error: ${outcome.text}" else outcome.text)
            }
        }
        return "That took too many steps, so I stopped. Try asking a bit more simply?"
    }

    /** Asks each provider in turn, starting with the one that worked last, until one answers. */
    private fun complete(): Pair<FreeProvider, JSONObject> {
        val available = providers()
        if (available.isEmpty()) throw IOException("No free AI key is set up.")
        val ordered = available.sortedBy { if (it.first.id == preferred) 0 else 1 }
        var lastError: Exception? = null
        for ((provider, key) in ordered) {
            try {
                return provider to completeWith(provider, key).also { preferred = provider.id }
            } catch (e: ApiError) {
                lastError = e
                // A bad request is about the conversation, not the provider, so don't hide it.
                if (e.status == 400 && ordered.size == 1) throw e
                Log.w("Blecky", "${provider.label} failed (${e.status}), trying the next brain", e)
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("No brain answered.")
    }

    /** One chat-completions call to [provider]; finds a working model by itself if the saved one is gone. */
    private fun completeWith(provider: FreeProvider, key: String): JSONObject {
        var model = settings.modelFor(provider.id).ifBlank { provider.preferredModels.first() }
        repeat(3) { attempt ->
            try {
                val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
                history.forEach { messages.put(forProvider(it, provider)) }
                val body = JSONObject()
                    .put("model", model)
                    .put("messages", messages)
                    .put("tools", toolsJson)
                    .put("tool_choice", "auto")
                    .put("temperature", 0.9)
                    .put("max_tokens", 1024)
                val response = JSONObject(request(provider, "${provider.baseUrl}/chat/completions", key, body.toString()))
                settings.setModelFor(provider.id, model)
                return response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            } catch (e: ApiError) {
                val modelGone = e.status == 404 || (e.status == 400 && e.message.contains("model", ignoreCase = true))
                // Some models occasionally produce a garbled tool call; asking again usually works.
                val badToolCall = e.status == 400 && e.message.contains("tool", ignoreCase = true) && attempt == 0
                when {
                    modelGone -> model = pickModel(provider, listModels(provider, key), exclude = model) ?: throw e
                    badToolCall -> Unit
                    else -> throw e
                }
            }
        }
        throw ApiError(provider, 0, "${provider.label} didn't answer after several tries.")
    }

    private fun listModels(provider: FreeProvider, key: String): List<String> = runCatching {
        val data = JSONObject(request(provider, "${provider.baseUrl}/models", key, null)).getJSONArray("data")
        (0 until data.length()).map { data.getJSONObject(it) }
            .filter { model ->
                // On OpenRouter, only free models that can use tools.
                provider.id != Settings.PROVIDER_OPENROUTER || (
                    model.getString("id").endsWith(":free") &&
                        model.optJSONArray("supported_parameters")?.toString()?.contains("\"tools\"") == true
                    )
            }
            .map { it.getString("id").removePrefix("models/") }
    }.getOrDefault(emptyList())

    private fun request(provider: FreeProvider, url: String, key: String, body: String?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Authorization", "Bearer $key")
            if (provider.id == Settings.PROVIDER_OPENROUTER) connection.setRequestProperty("X-Title", "Blecky")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (status !in 200..299) throw ApiError(provider, status, errorMessage(text))
            return text
        } finally {
            connection.disconnect()
        }
    }

    private fun describe(e: Exception): String {
        val many = providers().size > 1
        return when {
            e is ApiError && (e.status == 401 || e.status == 403 || e.message.contains("API key", ignoreCase = true)) ->
                "My ${e.provider.label} key isn't working. Check it in Settings; you can get a free one at ${e.provider.keyUrl}."
            e is ApiError && e.status == 429 ->
                if (many) "All my free brains are at their limit right now. Give me a minute." else
                    "I've used up the free ${e.provider.label} limit for the moment. Give me a minute, or add another free brain in Settings."
            e is ApiError && e.status >= 500 -> "${e.provider.label} is having a moment. Try me again in a bit."
            e is ApiError -> "${e.provider.label} said no to that (error ${e.status}): ${e.message}"
            e is IOException && e.message?.contains("No free AI key") == true ->
                "I need a free AI key first. Gemini is easiest: get one at ${FreeProvider.GEMINI.keyUrl} and paste it in Settings."
            e is IOException -> "I can't reach my brain right now. Is the internet on?"
            else -> "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    companion object {
        private const val MAX_STEPS = 10
        private const val MAX_MESSAGES = 40

        /** Which provider wrote an assistant message; stripped before sending. */
        private const val ORIGIN = "_by"

        /**
         * The copy of a history message to send to [provider]. Extra fields (like Gemini's thought
         * signatures or gpt-oss reasoning) only go back to the provider that wrote them, and only
         * where it accepts them; everyone else gets the plain OpenAI shape.
         */
        internal fun forProvider(message: JSONObject, provider: FreeProvider): JSONObject {
            val copy = JSONObject(message.toString())
            val origin = copy.remove(ORIGIN) as? String
            if (copy.optString("role") != "assistant") return copy
            if (origin == provider.id && provider.id == Settings.PROVIDER_GEMINI) return copy
            val plain = JSONObject().put("role", "assistant").put("content", copy.opt("content") ?: JSONObject.NULL)
            copy.optJSONArray("tool_calls")?.let { calls ->
                val clean = JSONArray()
                for (i in 0 until calls.length()) {
                    val call = calls.getJSONObject(i)
                    val function = call.getJSONObject("function")
                    clean.put(
                        JSONObject()
                            .put("id", call.optString("id"))
                            .put("type", "function")
                            .put("function", JSONObject().put("name", function.optString("name")).put("arguments", function.optString("arguments", "{}"))),
                    )
                }
                if (clean.length() > 0) plain.put("tool_calls", clean)
            }
            return plain
        }

        internal fun toolJson(tool: BleckyTool): JSONObject {
            val properties = JSONObject()
            tool.params.forEach { properties.put(it.name, JSONObject(it.schema())) }
            val parameters = JSONObject().put("type", "object").put("properties", properties)
            val required = tool.params.filter { it.required }.map { it.name }
            if (required.isNotEmpty()) parameters.put("required", JSONArray(required))
            return JSONObject()
                .put("type", "function")
                .put("function", JSONObject().put("name", tool.name).put("description", tool.description).put("parameters", parameters))
        }

        /** Keeps recent history only, starting on a user message so no tool result loses its call. */
        internal fun trimHistory(history: MutableList<JSONObject>) {
            if (history.size <= MAX_MESSAGES) return
            var cut = history.size - MAX_MESSAGES
            while (cut < history.size && history[cut].optString("role") != "user") cut++
            repeat(cut) { history.removeAt(0) }
        }

        /** Picks the best general chat model from a provider's list when the preferred ones are gone. */
        internal fun pickModel(provider: FreeProvider, ids: List<String>, exclude: String? = null): String? {
            val usable = ids.filter { it != exclude }
            provider.preferredModels.firstOrNull { it in usable }?.let { return it }
            val skip = listOf("image", "tts", "audio", "embed", "live", "vision", "guard", "whisper", "transcribe", "lite", "nano", "code")
            val chat = usable.filter { id -> skip.none { id.contains(it, ignoreCase = true) } }
            return when (provider.id) {
                Settings.PROVIDER_GEMINI ->
                    chat.filter { it.startsWith("gemini") && "flash" in it }
                        .sortedWith(compareBy<String> { "preview" in it || "exp" in it }.thenByDescending { versionOf(it) })
                        .firstOrNull()
                Settings.PROVIDER_OPENROUTER ->
                    listOf("gemma", "nemotron", "llama", "qwen", "deepseek", "gpt-oss")
                        .firstNotNullOfOrNull { family -> chat.firstOrNull { family in it } } ?: chat.firstOrNull()
                else ->
                    chat.firstOrNull { "120b" in it } ?: chat.firstOrNull { "70b" in it } ?: chat.firstOrNull()
            }
        }

        private fun versionOf(id: String): Double =
            Regex("(\\d+(?:\\.\\d+)?)").find(id)?.value?.toDoubleOrNull() ?: 0.0

        internal fun errorMessage(body: String): String = runCatching {
            val json = JSONObject(body)
            when (val error = json.opt("error")) {
                is JSONObject -> error.optString("message")
                is JSONArray -> error.optJSONObject(0)?.optJSONObject("error")?.optString("message")
                else -> null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(200)

        internal fun jsonToMap(json: JSONObject): Map<String, Any?> =
            json.keys().asSequence().associateWith { key -> unwrap(json.opt(key)) }

        private fun unwrap(value: Any?): Any? = when (value) {
            is JSONObject -> jsonToMap(value)
            is JSONArray -> (0 until value.length()).map { unwrap(value.opt(it)) }
            JSONObject.NULL -> null
            else -> value
        }
    }
}

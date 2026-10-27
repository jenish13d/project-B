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
            Settings.PROVIDER_GEMINI,
            "Google Gemini",
            "https://generativelanguage.googleapis.com/v1beta/openai",
            "aistudio.google.com/apikey",
            listOf("gemini-flash-latest", "gemini-3.8-flash", "gemini-2.5-flash"),
        )
        val GROQ = FreeProvider(
            Settings.PROVIDER_GROQ,
            "Groq",
            "https://api.groq.com/openai/v1",
            "console.groq.com/keys",
            listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile", "openai/gpt-oss-20b"),
        )
    }
}

/** Runs Blecky's tool loop on a free provider (Gemini or Groq) through their OpenAI-compatible APIs. */
class OpenAiEngine(
    private val context: Context,
    private val settings: Settings,
    private val tools: List<BleckyTool>,
    private val provider: FreeProvider,
) : Engine {
    private class ApiError(val status: Int, override val message: String) : IOException(message)

    private val toolsByName = tools.associateBy { it.name }
    private val toolsJson: JSONArray by lazy { JSONArray(tools.map(::toolJson)) }
    private val history = mutableListOf<JSONObject>()
    private var system = Persona.PROMPT
    private var lastTurnAt = 0L

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
            runTurn(key, ctx)
        } catch (e: Exception) {
            while (history.size > startSize) history.removeAt(history.size - 1)
            Log.w("Blecky", "${provider.label} request failed", e)
            describe(e)
        }
    }

    private suspend fun runTurn(key: String, ctx: ToolContext): String {
        repeat(MAX_STEPS) {
            val message = complete(key)
            // Send the assistant message back exactly as received: Gemini attaches thought
            // signatures to tool calls and needs them returned unchanged.
            if (provider.id == Settings.PROVIDER_GROQ) message.remove("reasoning")
            history += message.put("role", "assistant")
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

    /** One chat-completions call; finds a working model by itself if the saved one has gone away. */
    private fun complete(key: String): JSONObject {
        var model = settings.modelFor(provider.id).ifBlank { provider.preferredModels.first() }
        repeat(3) { attempt ->
            try {
                val body = JSONObject()
                    .put("model", model)
                    .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system)).also { m -> history.forEach { m.put(it) } })
                    .put("tools", toolsJson)
                    .put("tool_choice", "auto")
                    .put("temperature", 0.9)
                    .put("max_tokens", 1024)
                val response = JSONObject(post("${provider.baseUrl}/chat/completions", key, body.toString()))
                settings.setModelFor(provider.id, model)
                return response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            } catch (e: ApiError) {
                val modelGone = e.status == 404 || (e.status == 400 && e.message.contains("model", ignoreCase = true))
                // Groq sometimes rejects a garbled tool call from the model; asking again usually works.
                val badToolCall = e.status == 400 && e.message.contains("tool", ignoreCase = true) && attempt == 0
                when {
                    modelGone -> model = pickModel(provider, listModels(key), exclude = model) ?: throw e
                    badToolCall -> Unit
                    else -> throw e
                }
            }
        }
        throw IOException("${provider.label} didn't answer after several tries.")
    }

    private fun listModels(key: String): List<String> = runCatching {
        val data = JSONObject(get("${provider.baseUrl}/models", key)).getJSONArray("data")
        (0 until data.length()).map { data.getJSONObject(it).getString("id").removePrefix("models/") }
    }.getOrDefault(emptyList())

    private fun post(url: String, key: String, body: String): String = request(url, key, body)

    private fun get(url: String, key: String): String = request(url, key, null)

    private fun request(url: String, key: String, body: String?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Authorization", "Bearer $key")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (status !in 200..299) throw ApiError(status, errorMessage(text))
            return text
        } finally {
            connection.disconnect()
        }
    }

    private fun describe(e: Exception): String = when {
        e is ApiError && (e.status == 401 || e.status == 403 || e.message.contains("API key", ignoreCase = true)) ->
            "My ${provider.label} key isn't working. Check it in Settings; you can get a free one at ${provider.keyUrl}."
        e is ApiError && e.status == 429 ->
            "I've used up the free ${provider.label} limit for the moment. Give me a minute, or switch to the other free brain in Settings."
        e is ApiError && e.status >= 500 -> "${provider.label} is having a moment. Try me again in a bit."
        e is ApiError -> "${provider.label} said no to that (error ${e.status}): ${e.message}"
        e is IOException -> "I can't reach ${provider.label} right now. Is the internet on?"
        else -> "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
    }

    companion object {
        private const val MAX_STEPS = 10
        private const val MAX_MESSAGES = 40

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
            val skip = listOf("image", "tts", "audio", "embed", "live", "vision", "guard", "whisper", "transcribe", "lite", "nano")
            val chat = usable.filter { id -> skip.none { id.contains(it, ignoreCase = true) } }
            return when (provider.id) {
                Settings.PROVIDER_GEMINI ->
                    chat.filter { it.startsWith("gemini") && "flash" in it }
                        .sortedWith(compareBy<String> { "preview" in it || "exp" in it }.thenByDescending { versionOf(it) })
                        .firstOrNull()
                else ->
                    chat.firstOrNull { "120b" in it } ?: chat.firstOrNull { "70b" in it } ?: chat.firstOrNull()
            }
        }

        private fun versionOf(id: String): Double =
            Regex("(\\d+(?:\\.\\d+)?)").find(id)?.value?.toDoubleOrNull() ?: 0.0

        internal fun errorMessage(body: String): String = runCatching {
            val json = JSONObject(body)
            val error = json.opt("error")
            when (error) {
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

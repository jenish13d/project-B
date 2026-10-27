package com.blecky.assistant

import com.blecky.assistant.brain.FreeProvider
import com.blecky.assistant.brain.OpenAiEngine
import com.blecky.assistant.brain.Persona
import com.blecky.assistant.tools.WebParse
import com.blecky.assistant.voice.SpeechText
import com.blecky.assistant.tools.allTools
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeBrainTest {
    @Test
    fun `tools are described in OpenAI function format and stay small`() {
        val json = JSONArray(allTools().map(OpenAiEngine::toolJson))
        val call = (0 until json.length()).map { json.getJSONObject(it) }.first { it.getJSONObject("function").getString("name") == "call" }
        val params = call.getJSONObject("function").getJSONObject("parameters")
        assertEquals("object", params.getString("type"))
        assertEquals(listOf("contact"), (0 until params.getJSONArray("required").length()).map { params.getJSONArray("required").getString(it) })
        assertFalse(json.toString().contains("\"required\":[]"))
        // Free tiers limit tokens per minute; keep the tool list well under ~4k tokens (~16k chars).
        assertTrue("tools JSON is ${json.toString().length} chars", json.toString().length < 16_000)
    }

    @Test
    fun `picks a current model when the preferred ones are gone`() {
        val gemini = listOf("gemini-2.0-flash", "gemini-4.1-flash", "gemini-4.2-flash-preview", "gemini-4.1-flash-lite", "gemini-4.1-flash-image", "embedding-001")
        assertEquals("gemini-4.1-flash", OpenAiEngine.pickModel(FreeProvider.GEMINI, gemini))
        assertEquals("gemini-flash-latest", OpenAiEngine.pickModel(FreeProvider.GEMINI, gemini + "gemini-flash-latest"))
        val groq = listOf("whisper-large-v3", "llama-guard-4", "qwen/qwen3-32b", "llama-4-70b-chat")
        assertEquals("llama-4-70b-chat", OpenAiEngine.pickModel(FreeProvider.GROQ, groq))
        assertEquals("llama-3.3-70b-versatile", OpenAiEngine.pickModel(FreeProvider.GROQ, groq + "llama-3.3-70b-versatile", exclude = "openai/gpt-oss-120b"))
        assertNull(OpenAiEngine.pickModel(FreeProvider.GEMINI, listOf("embedding-001")))
    }

    @Test
    fun `history trims to start at a user turn`() {
        val history = mutableListOf<JSONObject>()
        repeat(15) {
            history += JSONObject().put("role", "user")
            history += JSONObject().put("role", "assistant").put("tool_calls", JSONArray())
            history += JSONObject().put("role", "tool")
        }
        OpenAiEngine.trimHistory(history)
        assertTrue(history.size <= 40)
        assertEquals("user", history.first().getString("role"))
    }

    @Test
    fun `error bodies from both providers are readable`() {
        assertEquals("Invalid API key", OpenAiEngine.errorMessage("""{"error":{"message":"Invalid API key","type":"invalid_request_error"}}"""))
        assertEquals("Quota exceeded", OpenAiEngine.errorMessage("""[{"error":{"code":429,"message":"Quota exceeded"}}]""".let { "{\"error\":$it}" }))
        assertEquals("plain text", OpenAiEngine.errorMessage("plain text"))
    }

    @Test
    fun `tool arguments become plain maps`() {
        val map = OpenAiEngine.jsonToMap(JSONObject("""{"contact":"Jayesh","n":5,"list":[1,{"a":null}]}"""))
        assertEquals("Jayesh", map["contact"])
        assertEquals(5, map["n"])
        assertEquals(listOf(1, mapOf("a" to null)), map["list"])
    }

    @Test
    fun `news headlines and weather codes are parsed`() {
        val rss = """<rss><channel><title>Google News</title>
            <item><title>India win thriller - NDTV Sports</title><pubDate>Tue, 27 Oct 2026 10:00:00 GMT</pubDate><source url="x">NDTV Sports</source></item>
            <item><title>Rain in Ahmedabad &amp; Surat - TOI</title><pubDate>Tue, 27 Oct 2026 09:00:00 GMT</pubDate><source url="y">TOI</source></item>
            </channel></rss>"""
        val news = WebParse.headlines(rss)
        assertEquals(2, news.size)
        assertEquals("India win thriller", news[0].title)
        assertEquals("Rain in Ahmedabad & Surat", news[1].title)
        assertEquals("thunderstorm", WebParse.weatherWords(95))
        assertEquals("clear sky", WebParse.weatherWords(0))
    }

    @Test
    fun `messages carry the time and mark scheduler prompts`() {
        assertTrue(Persona.userMessage("hi", fromScheduler = false).matches(Regex("^\\[.+] hi$")))
        assertTrue(Persona.userMessage("check in", fromScheduler = true).contains("Not the user speaking"))
    }

    @Test
    fun `gemini keeps its own extra fields, other brains get plain messages`() {
        val fromGemini = JSONObject()
            .put("role", "assistant").put("content", JSONObject.NULL).put("_by", "gemini")
            .put("tool_calls", JSONArray().put(
                JSONObject().put("id", "c1").put("type", "function")
                    .put("function", JSONObject().put("name", "call").put("arguments", "{\"contact\":\"Jash\"}"))
                    .put("extra_content", JSONObject().put("google", JSONObject().put("thought_signature", "sig"))),
            ))
        val toGemini = OpenAiEngine.forProvider(fromGemini, FreeProvider.GEMINI)
        assertTrue(toGemini.toString().contains("thought_signature"))
        assertFalse(toGemini.has("_by"))
        val toGroq = OpenAiEngine.forProvider(fromGemini, FreeProvider.GROQ)
        assertFalse(toGroq.toString().contains("thought_signature"))
        assertEquals("call", toGroq.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("name"))
        val reasoning = JSONObject().put("role", "assistant").put("content", "hi").put("reasoning", "...").put("_by", "groq")
        assertFalse(OpenAiEngine.forProvider(reasoning, FreeProvider.GROQ).has("reasoning"))
        val user = JSONObject().put("role", "user").put("content", "hi")
        assertEquals("hi", OpenAiEngine.forProvider(user, FreeProvider.CEREBRAS).getString("content"))
    }

    @Test
    fun `openrouter picks a strong free model`() {
        val ids = listOf("liquid/lfm-2.5-2.6b:free", "google/gemma-4-26b-a4b-it:free", "cohere/north-mini-code:free")
        assertEquals("google/gemma-4-26b-a4b-it:free", OpenAiEngine.pickModel(FreeProvider.OPENROUTER, ids))
        assertEquals("google/gemma-4-31b-it:free", OpenAiEngine.pickModel(FreeProvider.OPENROUTER, ids + "google/gemma-4-31b-it:free"))
    }

    @Test
    fun `replies are voiced by script`() {
        assertEquals(SpeechText.Script.GUJARATI, SpeechText.script("કેમ છો Jai? બધું બરાબર?"))
        assertEquals(SpeechText.Script.DEVANAGARI, SpeechText.script("अरे जय, कैसे हो?"))
        assertEquals(SpeechText.Script.LATIN, SpeechText.script("Arre Jai, kaise ho? Sab theek?"))
        assertEquals(SpeechText.Script.LATIN, SpeechText.script("Calling Jayesh now."))
    }
}

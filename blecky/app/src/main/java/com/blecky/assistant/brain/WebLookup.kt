package com.blecky.assistant.brain

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.blecky.assistant.tools.Args
import com.blecky.assistant.tools.Memory
import com.blecky.assistant.tools.SearchWeb
import com.blecky.assistant.tools.ToolContext
import com.blecky.assistant.tools.Weather

/**
 * The on-phone AI's research step: when a question needs fresh facts and the phone is online, look
 * them up (free, no key) and hand them to the model with the question. Small models can't be
 * trusted to call a search tool themselves, so this decides by itself.
 */
object WebLookup {
    private val WEATHER = Regex(
        "\\b(weather|rain|raining|temperature|forecast|humid|humidity|umbrella|hot outside|cold outside|mausam|baarish|barish)\\b|" +
            "मौसम|बारिश|तापमान|હવામાન|વરસાદ|તાપમાન",
        RegexOption.IGNORE_CASE,
    )
    private val CURRENT = Regex(
        "\\b(news|latest|today'?s|tonight|yesterday|score|scores|match|won|winner|price|prices|rate|stock|release|released|launch|" +
            "update|election|results?|trending|happening|ipl|cricket|football|who is|who's|who was|what is|what's|when is|when did|" +
            "where is|how much|how many|kab|kaun|kitna|kitne|khabar)\\b|समाचार|ख़बर|खबर|कौन|कब|कितना|સમાચાર|કોણ|ક્યારે|કેટલું",
        RegexOption.IGNORE_CASE,
    )

    /** Questions about Blecky or the user themselves never need the web. */
    private val PERSONAL = Regex(
        "\\b(you|your|yourself|i|me|my|mine|tum|tumhara|aap|aapka|mera|mujhe)\\b|तुम|आप|मेरा|मुझे|તું|તમે|મારું|મને",
        RegexOption.IGNORE_CASE,
    )
    private val CITY = Regex("\\b(?:in|at|for|of) ([A-Za-z][A-Za-z .'-]{2,30}?)(?:\\s+(?:today|tomorrow|now|this week|tonight))?[?.!]*$", RegexOption.IGNORE_CASE)
    private val HOME_CITY = Regex("(?i)(?:lives in|living in|from|based in|stays in|city is|home is in) ([a-z][a-z .'-]{2,30})")
    private val FILLER = Regex("^(?:hey |ok |okay )?(?:blecky|bleck|blackie)?[ ,]*(?:please |can you |could you |tell me |do you know )*", RegexOption.IGNORE_CASE)

    enum class Need { NONE, WEATHER, SEARCH }

    private val SMALL_TALK = Regex("(?i)what'?s up|what is up|how are you|what'?s new")

    fun need(text: String): Need {
        val question = text.replace(SMALL_TALK, "")
        return when {
            WEATHER.containsMatchIn(question) -> Need.WEATHER
            CURRENT.containsMatchIn(question) && !PERSONAL.containsMatchIn(question) -> Need.SEARCH
            else -> Need.NONE
        }
    }

    /** The city a weather question is about, or the user's home city from memory. */
    fun city(text: String, memories: List<String>): String? =
        CITY.find(text.trim())?.groupValues?.get(1)?.trim()
            ?: memories.firstNotNullOfOrNull { HOME_CITY.find(it)?.groupValues?.get(1)?.trim() }

    fun query(text: String): String = text.replace(FILLER, "").replace(Regex("[?!.]+$"), "").trim().ifEmpty { text }

    /** Whether the phone has a network. If Android won't say, assume yes and let the request decide. */
    fun isOnline(context: Context): Boolean = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    /** Fresh facts for [text], or null if it doesn't need any or the lookup fails. */
    suspend fun lookup(text: String, ctx: ToolContext): String? {
        val result = runCatching {
            when (need(text)) {
                Need.NONE -> null
                Need.WEATHER -> {
                    val memories = Memory.store(ctx.context).load().map { it.getString("text") }
                    val city = city(text, memories) ?: return null
                    Weather.run(Args(mapOf("city" to city)), ctx)
                }
                Need.SEARCH -> SearchWeb.run(Args(mapOf("query" to query(text))), ctx)
            }
        }.getOrNull() ?: return null
        // Every word costs time on a phone CPU, so keep it short.
        return result.take(1_200)
    }
}

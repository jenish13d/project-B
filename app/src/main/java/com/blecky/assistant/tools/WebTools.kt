package com.blecky.assistant.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Free, keyless web lookups: Google News RSS for headlines, Wikipedia for facts, Open-Meteo for weather. */
fun webTools(): List<BleckyTool> = listOf(SearchWeb, Weather)

private const val USER_AGENT = "Blecky/1.0 (personal assistant for Android)"

internal suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 8_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        if (connection.responseCode !in 200..299) throw ToolError("The lookup failed (HTTP ${connection.responseCode}).")
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")

internal object WebParse {
    data class Headline(val title: String, val source: String, val date: String)

    private val ITEM = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
    private val TITLE = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
    private val SOURCE = Regex("<source[^>]*>(.*?)</source>", RegexOption.DOT_MATCHES_ALL)
    private val DATE = Regex("<pubDate>(.*?)</pubDate>", RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("<[^>]+>")

    fun headlines(rss: String, limit: Int = 6): List<Headline> = ITEM.findAll(rss).take(limit).map { item ->
        val body = item.groupValues[1]
        val source = SOURCE.find(body)?.groupValues?.get(1)?.let(::unescape).orEmpty()
        var title = TITLE.find(body)?.groupValues?.get(1)?.let(::unescape).orEmpty()
        // Google News appends " - Source" to each title.
        if (source.isNotEmpty()) title = title.removeSuffix(" - $source")
        Headline(title, source, DATE.find(body)?.groupValues?.get(1)?.take(16).orEmpty())
    }.filter { it.title.isNotBlank() }.toList()

    fun stripTags(html: String) = unescape(html.replace(TAG, ""))

    fun unescape(text: String) = text
        .replace("<![CDATA[", "").replace("]]>", "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .trim()

    /** WMO weather codes used by Open-Meteo, in words. */
    fun weatherWords(code: Int): String = when (code) {
        0 -> "clear sky"
        1 -> "mostly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "foggy"
        51, 53, 55 -> "drizzle"
        56, 57 -> "freezing drizzle"
        61 -> "light rain"
        63 -> "rain"
        65 -> "heavy rain"
        66, 67 -> "freezing rain"
        71, 73, 75, 77 -> "snow"
        80, 81 -> "rain showers"
        82 -> "violent rain showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorm"
        96, 99 -> "thunderstorm with hail"
        else -> "unsettled weather"
    }
}

object SearchWeb : BleckyTool(
    "search_web",
    "Look up current news, scores, prices or facts on the internet. Returns recent headlines and an encyclopedia summary.",
    listOf(Param("query", "string", "What to search for, in a few keywords")),
) {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        val query = args.string("query")
        val parts = mutableListOf<String>()
        runCatching {
            val rss = httpGet("https://news.google.com/rss/search?q=${encode(query)}&hl=en-IN&gl=IN&ceid=IN:en")
            val news = WebParse.headlines(rss)
            if (news.isNotEmpty()) {
                parts += "Latest headlines:\n" + news.joinToString("\n") { "- ${it.title} (${it.source}, ${it.date})" }
            }
        }
        runCatching {
            val json = JSONObject(httpGet("https://en.wikipedia.org/w/rest.php/v1/search/page?q=${encode(query)}&limit=2"))
            val pages = json.optJSONArray("pages")
            if (pages != null && pages.length() > 0) {
                parts += "Encyclopedia:\n" + (0 until pages.length()).joinToString("\n") { i ->
                    val page = pages.getJSONObject(i)
                    "- ${page.optString("title")}: ${page.optString("description")}. ${WebParse.stripTags(page.optString("excerpt"))}"
                }
            }
        }
        if (parts.isEmpty()) throw ToolError("I couldn't find anything online for that.")
        return parts.joinToString("\n\n")
    }
}

object Weather : BleckyTool(
    "get_weather",
    "Current weather and the next few days' forecast for a city.",
    listOf(
        Param("city", "string", "City name, e.g. Ahmedabad. Use what you remember about where the user lives if they don't say."),
        Param("days", "integer", "Days of forecast, 1-7 (default 3)", required = false),
    ),
) {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        val city = args.string("city")
        val geo = JSONObject(httpGet("https://geocoding-api.open-meteo.com/v1/search?name=${encode(city)}&count=1"))
        val place = geo.optJSONArray("results")?.optJSONObject(0) ?: throw ToolError("I couldn't find a place called $city.")
        val days = (args.optInt("days") ?: 3).coerceIn(1, 7)
        val forecast = JSONObject(
            httpGet(
                "https://api.open-meteo.com/v1/forecast?latitude=${place.getDouble("latitude")}&longitude=${place.getDouble("longitude")}" +
                    "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=$days",
            ),
        )
        val now = forecast.getJSONObject("current")
        val daily = forecast.getJSONObject("daily")
        val lines = mutableListOf(
            "${place.optString("name")}, ${place.optString("country")} now: ${now.optDouble("temperature_2m")}°C " +
                "(feels like ${now.optDouble("apparent_temperature")}°C), ${WebParse.weatherWords(now.optInt("weather_code"))}, " +
                "humidity ${now.optInt("relative_humidity_2m")}%, wind ${now.optDouble("wind_speed_10m")} km/h.",
        )
        val dates = daily.getJSONArray("time")
        for (i in 0 until dates.length()) {
            lines += "${dates.getString(i)}: ${WebParse.weatherWords(daily.getJSONArray("weather_code").optInt(i))}, " +
                "${daily.getJSONArray("temperature_2m_min").optDouble(i)} to ${daily.getJSONArray("temperature_2m_max").optDouble(i)}°C, " +
                "rain chance ${daily.getJSONArray("precipitation_probability_max").optInt(i)}%"
        }
        return lines.joinToString("\n")
    }
}

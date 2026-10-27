package com.blecky.assistant.voice

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Optional cloud voice from ElevenLabs: the most lifelike, needs the user's own ElevenLabs API key. */
class ElevenLabsVoice {
    @Volatile
    private var current: PcmPlayer? = null

    /**
     * Speaks [text]. Throws [IOException] before any audio plays if the request fails, so the
     * caller can fall back to another voice.
     */
    fun speak(apiKey: String, voiceId: String, text: String) {
        val url = URL("https://api.elevenlabs.io/v1/text-to-speech/${voiceId.ifBlank { DEFAULT_VOICE }}/stream?output_format=pcm_22050")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("xi-api-key", apiKey)
            connection.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject()
                .put("text", text)
                .put("model_id", "eleven_flash_v2_5")
                .put("voice_settings", JSONObject().put("stability", 0.45).put("similarity_boost", 0.8).put("style", 0.3))
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            if (connection.responseCode != 200) {
                val error = connection.errorStream?.bufferedReader()?.readText()?.take(300)
                throw IOException("ElevenLabs error ${connection.responseCode}: $error")
            }
            val player = PcmPlayer(22_050, floatSamples = false)
            current = player
            try {
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8 * 1024)
                    while (!player.stopped) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        player.write(buffer, n)
                    }
                }
            } finally {
                player.finish()
                current = null
            }
        } finally {
            connection.disconnect()
        }
    }

    fun stop() {
        current?.stop()
    }

    companion object {
        /** "Rachel": a calm, warm young American woman from ElevenLabs' default voices. */
        const val DEFAULT_VOICE = "21m00Tcm4TlvDq8ikWAM"
    }
}

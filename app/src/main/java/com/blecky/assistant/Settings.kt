package com.blecky.assistant

import android.content.Context

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("blecky", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString("model", value.trim()).apply()

    /** Whether the user wants the always-on "Hey Blecky" listener running. */
    var listening: Boolean
        get() = prefs.getBoolean("listening", false)
        set(value) = prefs.edit().putBoolean("listening", value).apply()

    /** What Blecky calls the user. */
    var userName: String
        get() = prefs.getString("user_name", "") ?: ""
        set(value) = prefs.edit().putString("user_name", value.trim()).apply()

    /** Chosen text-to-speech voice name; empty picks a friendly female English voice. */
    var voiceName: String
        get() = prefs.getString("voice_name", "") ?: ""
        set(value) = prefs.edit().putString("voice_name", value).apply()

    /** Only wake for the enrolled owner's voice. */
    var voiceLock: Boolean
        get() = prefs.getBoolean("voice_lock", false)
        set(value) = prefs.edit().putBoolean("voice_lock", value).apply()

    /** The owner's averaged voiceprint, comma-separated. */
    var voiceProfile: String
        get() = prefs.getString("voice_profile", "") ?: ""
        set(value) = prefs.edit().putString("voice_profile", value).apply()

    /** Minimum voice match (cosine similarity) needed to wake when the voice lock is on. */
    var voiceThreshold: Float
        get() = prefs.getFloat("voice_threshold", DEFAULT_VOICE_THRESHOLD)
        set(value) = prefs.edit().putFloat("voice_threshold", value).apply()

    /** Match score of the most recent wake attempt, or -1 if none. */
    var lastVoiceScore: Float
        get() = prefs.getFloat("last_voice_score", -1f)
        set(value) = prefs.edit().putFloat("last_voice_score", value).apply()

    /** Minute of the day for Blecky's daily check-in, or -1 for off. */
    var checkInMinute: Int
        get() = prefs.getInt("check_in_minute", 9 * 60)
        set(value) = prefs.edit().putInt("check_in_minute", value).apply()

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        const val DEFAULT_VOICE_THRESHOLD = 0.45f
    }
}

package com.blecky.assistant

import android.content.Context

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("blecky", Context.MODE_PRIVATE)

    /**
     * Which AI runs Blecky's brain: "auto" combines every free provider that has a key, or one of
     * "gemini", "groq", "cerebras", "openrouter" (all free), or "claude" (needs Anthropic credit).
     */
    var provider: String
        get() = prefs.getString("provider", PROVIDER_AUTO) ?: PROVIDER_AUTO
        set(value) = prefs.edit().putString("provider", value).apply()

    /** Anthropic (Claude) API key. */
    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()

    fun keyFor(provider: String): String = when (provider) {
        PROVIDER_CLAUDE -> apiKey
        else -> prefs.getString("key_$provider", "") ?: ""
    }

    fun setKeyFor(provider: String, key: String) {
        if (provider == PROVIDER_CLAUDE) apiKey = key else prefs.edit().putString("key_$provider", key.trim()).apply()
    }

    /** Key for the provider in use; in auto mode, any free key. */
    val activeKey: String
        get() = if (provider == PROVIDER_AUTO) FREE_PROVIDERS.map(::keyFor).firstOrNull { it.isNotBlank() } ?: "" else keyFor(provider)

    /** Model picked automatically for a free provider (their model names change often). */
    fun modelFor(provider: String): String = prefs.getString("model_$provider", "") ?: ""

    fun setModelFor(provider: String, model: String) = prefs.edit().putString("model_$provider", model).apply()

    /** What the user speaks: "mix" (English, Hindi and Gujarati), or just "en", "hi" or "gu". */
    var speechMode: String
        get() = prefs.getString("speech_mode", "mix") ?: "mix"
        set(value) = prefs.edit().putString("speech_mode", value).apply()

    /** Main language for speech recognition, then the others it may also hear. */
    fun recognitionLanguages(): Pair<String, List<String>> = when (speechMode) {
        "hi" -> "hi-IN" to listOf("en-IN")
        "gu" -> "gu-IN" to listOf("en-IN")
        "en" -> "en-IN" to emptyList()
        else -> "en-IN" to listOf("hi-IN", "gu-IN")
    }

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

    /** Which on-phone AI model to use (see OfflineModels.MODELS). */
    var offlineModel: String
        get() = prefs.getString("offline_model", DEFAULT_OFFLINE_MODEL) ?: DEFAULT_OFFLINE_MODEL
        set(value) = prefs.edit().putString("offline_model", value).apply()

    /** Also wake on "Bleck" said on its own. Off by default: short sounds get mistaken for it. */
    var wakeOnNameAlone: Boolean
        get() = prefs.getBoolean("wake_name_alone", false)
        set(value) = prefs.edit().putBoolean("wake_name_alone", value).apply()

    /** Chosen offline neural voice (see NeuralVoices.PACKS). */
    var voicePack: String
        get() = prefs.getString("voice_pack", "jenny") ?: "jenny"
        set(value) = prefs.edit().putString("voice_pack", value).apply()

    /** Optional ElevenLabs key for the most lifelike cloud voice. */
    var elevenLabsKey: String
        get() = prefs.getString("eleven_key", "") ?: ""
        set(value) = prefs.edit().putString("eleven_key", value.trim()).apply()

    var elevenLabsVoice: String
        get() = prefs.getString("eleven_voice", "") ?: ""
        set(value) = prefs.edit().putString("eleven_voice", value.trim()).apply()

    /** How long Blecky keeps listening for a follow-up before going back to waiting for her name. */
    var followUpSeconds: Int
        get() = prefs.getInt("follow_up_seconds", 45)
        set(value) = prefs.edit().putInt("follow_up_seconds", value).apply()

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        const val PROVIDER_GEMINI = "gemini"
        const val PROVIDER_GROQ = "groq"
        const val PROVIDER_CLAUDE = "claude"
        const val PROVIDER_CEREBRAS = "cerebras"
        const val PROVIDER_OPENROUTER = "openrouter"
        const val PROVIDER_AUTO = "auto"
        const val PROVIDER_OFFLINE = "offline"
        const val DEFAULT_OFFLINE_MODEL = "gemma4-e2b"
        val FREE_PROVIDERS = listOf(PROVIDER_GEMINI, PROVIDER_GROQ, PROVIDER_CEREBRAS, PROVIDER_OPENROUTER)
        const val DEFAULT_VOICE_THRESHOLD = 0.45f
    }
}

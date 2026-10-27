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

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
    }
}

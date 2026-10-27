package com.blecky.assistant

import android.os.Handler
import android.os.Looper

/** In-memory transcript shared by the background service and the screen. */
object ConversationLog {
    data class Entry(val fromUser: Boolean, val text: String)

    private val entries = mutableListOf<Entry>()
    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun all(): List<Entry> = entries.toList()

    fun user(text: String) = add(Entry(true, text))

    fun blecky(text: String) = add(Entry(false, text))

    fun addListener(listener: () -> Unit) = main.post { listeners += listener }

    fun removeListener(listener: () -> Unit) = main.post { listeners -= listener }

    @Synchronized
    private fun add(entry: Entry) {
        entries += entry
        if (entries.size > 200) entries.removeAt(0)
        main.post { listeners.toList().forEach { it() } }
    }
}

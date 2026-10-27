package com.blecky.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.blecky.assistant.tools.allTools
import com.blecky.assistant.updates.Diagnostics
import com.blecky.assistant.updates.LiveLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.blecky.assistant.voice.VoiceIO

class BleckyApp : Application() {
    val settings by lazy { Settings(this) }
    val voice by lazy { VoiceIO(this, settings) }
    val brain by lazy { Brain(this, settings, allTools()) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Runs [block] in the background for as long as the app lives. */
    fun background(block: suspend () -> Unit) {
        appScope.launch { block() }
    }

    /** Whether any brain can answer: a free key, Claude, or a downloaded on-phone model. */
    fun hasBrain() = settings.activeKey.isNotBlank() || brain.offline.available()

    override fun onCreate() {
        super.onCreate()
        Diagnostics.installCrashHandler(this)
        LiveLink.schedule(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_LISTENING, "Listening for Hey Blecky", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    companion object {
        const val CHANNEL_LISTENING = "listening"
        const val CHANNEL_REMINDERS = "reminders"
    }
}

val android.content.Context.blecky: BleckyApp get() = applicationContext as BleckyApp

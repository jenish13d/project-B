package com.blecky.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.blecky.assistant.tools.allTools
import com.blecky.assistant.voice.VoiceIO

class BleckyApp : Application() {
    val settings by lazy { Settings(this) }
    val voice by lazy { VoiceIO(this, settings) }
    val brain by lazy { Brain(this, settings, allTools()) }

    /** Whether any brain can answer: a free key, Claude, or a downloaded on-phone model. */
    fun hasBrain() = settings.activeKey.isNotBlank() || brain.offline.available()

    override fun onCreate() {
        super.onCreate()
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

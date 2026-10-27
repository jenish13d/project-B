package com.blecky.assistant.voice

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.blecky.assistant.BleckyApp
import com.blecky.assistant.ConversationLog
import com.blecky.assistant.MainActivity
import com.blecky.assistant.R
import com.blecky.assistant.blecky
import com.blecky.assistant.tools.UserChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Foreground service that keeps the microphone open (screen on or off) and waits for
 * "Hey Blecky" or "Bleck" using an offline recognizer, optionally checks it's the owner's voice,
 * then runs a spoken conversation. It also lets Blecky start conversations itself (check-ins).
 */
class WakeWordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null
    private var ownerVoice: FloatArray? = null
    private var session: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val voiceChannel = object : UserChannel {
        override suspend fun confirm(question: String): Boolean {
            ConversationLog.blecky(question)
            blecky.voice.speak(question)
            val answer = blecky.voice.listen()
            answer?.let { ConversationLog.user(it) }
            return WakeWord.isYes(answer)
        }
    }

    private val wakeListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) = check(hypothesis, isFinal = false)
        override fun onResult(hypothesis: String?) = check(hypothesis, isFinal = true)
        override fun onFinalResult(hypothesis: String?) = Unit
        override fun onTimeout() = Unit
        override fun onError(exception: Exception?) {
            Log.w(TAG, "Wake listener failed; restarting", exception)
            stopWakeListener()
            scope.launch {
                delay(2_000)
                startWakeListener()
            }
        }

        private fun check(hypothesis: String?, isFinal: Boolean) {
            val result = hypothesis?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
            if (!WakeWord.matches(result.optString(if (isFinal) "text" else "partial"), isFinal)) return
            val owner = ownerVoice
            if (owner == null) {
                startSession()
                return
            }
            // The voiceprint only comes with the final result, so wait for it.
            if (!isFinal) return
            val score = SpeakerProfile.fromResult(result)?.let { SpeakerProfile.similarity(it, owner) } ?: 0f
            blecky.settings.lastVoiceScore = score
            if (score >= blecky.settings.voiceThreshold) startSession() else Log.i(TAG, "Wake phrase from another voice ($score)")
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: if (blecky.settings.listening) ACTION_LISTEN else ACTION_STOP
        if (action == ACTION_STOP) {
            blecky.settings.listening = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_LISTEN -> {
                blecky.settings.listening = true
                startWakeListener()
            }
            ACTION_TALK -> startSession(showScreen = false)
            ACTION_SAY -> intent?.getStringExtra(EXTRA_TEXT)?.let(::announce)
            ACTION_CHECK_IN -> intent?.getStringExtra(EXTRA_TEXT)?.let { startSession(checkIn = it) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        VoiceState.mode = VoiceState.Mode.OFF
        scope.cancel()
        blecky.voice.stopSpeaking()
        stopWakeListener()
        super.onDestroy()
    }

    private fun enterForeground(): Boolean = try {
        startForeground(NOTIFICATION_ID, listeningNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        true
    } catch (e: Exception) {
        // Android won't let a microphone service start from the background (e.g. after a restart).
        Log.w(TAG, "Could not start in foreground", e)
        notifyRestartNeeded(this)
        false
    }

    @SuppressLint("WakelockTimeout")
    private fun startWakeListener() {
        if (speech != null || session?.isActive == true) return
        scope.launch {
            val settings = blecky.settings
            val owner = SpeakerProfile.decode(settings.voiceProfile)?.takeIf { settings.voiceLock }
            val rec = withContext(Dispatchers.IO) {
                runCatching {
                    Recognizer(ModelInstaller.speech(this@WakeWordService), SAMPLE_RATE, WakeWord.GRAMMAR).apply {
                        if (owner != null) setSpeakerModel(ModelInstaller.speaker(this@WakeWordService))
                    }
                }.onFailure { Log.e(TAG, "Could not load wake word models", it) }.getOrNull()
            }
            if (rec == null) {
                ConversationLog.blecky("My offline voice models are missing. Open Blecky and tap Start listening to download them.")
                stopSelf()
                return@launch
            }
            if (speech != null || session?.isActive == true) {
                rec.close()
                return@launch
            }
            ownerVoice = owner
            recognizer = rec
            speech = SpeechService(rec, SAMPLE_RATE).also { it.startListening(wakeListener) }
            VoiceState.mode = VoiceState.Mode.WAITING
            // Held only while waiting for the wake word, so the CPU keeps processing audio with the screen off.
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "blecky:wake")
                .also { it.acquire() }
        }
    }

    private fun stopWakeListener() {
        speech?.let {
            it.stop()
            it.shutdown()
        }
        speech = null
        recognizer?.close()
        recognizer = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    /**
     * A spoken conversation. Woken by the user, Blecky greets them; for a [checkIn], Blecky
     * opens with something it wanted to say. Either way it keeps listening for follow-ups.
     */
    private fun startSession(showScreen: Boolean = true, checkIn: String? = null) {
        if (session?.isActive == true) return
        stopWakeListener()
        session = scope.launch {
            try {
                if (showScreen) showOnScreen()
                val opener = if (checkIn != null) think { blecky.brain.ask(checkIn, voiceChannel, fromScheduler = true) } else {
                    WakeWord.greeting(blecky.settings.userName)
                }
                ConversationLog.blecky(opener)
                blecky.voice.speak(opener)
                // Keep the conversation open: no need to say her name again until it goes quiet.
                var quietSince = System.currentTimeMillis()
                var instantFailures = 0
                while (isActive) {
                    val started = System.currentTimeMillis()
                    val heard = blecky.voice.listen()
                    if (heard == null) {
                        // The recognizer can fail instantly (busy, offline); don't spin on it.
                        instantFailures = if (System.currentTimeMillis() - started < 1_000) instantFailures + 1 else 0
                        if (instantFailures >= 3) break
                        if (System.currentTimeMillis() - quietSince > blecky.settings.followUpSeconds * 1000L) break
                        delay(250)
                        continue
                    }
                    instantFailures = 0
                    ConversationLog.user(heard)
                    if (WakeWord.isGoodbye(heard)) {
                        val bye = WakeWord.goodbye()
                        ConversationLog.blecky(bye)
                        blecky.voice.speak(bye)
                        break
                    }
                    val reply = think { blecky.brain.ask(heard, voiceChannel) }
                    ConversationLog.blecky(reply)
                    blecky.voice.speak(reply)
                    quietSince = System.currentTimeMillis()
                }
            } finally {
                session = null
                resumeOrStop()
            }
        }
    }

    private inline fun <T> think(block: () -> T): T {
        VoiceState.mode = VoiceState.Mode.THINKING
        return try {
            block()
        } finally {
            VoiceState.mode = VoiceState.Mode.WAITING
        }
    }

    private fun announce(text: String) {
        if (session?.isActive == true) return
        session = scope.launch {
            try {
                stopWakeListener()
                ConversationLog.blecky(text)
                blecky.voice.speak(text)
            } finally {
                session = null
                resumeOrStop()
            }
        }
    }

    private fun resumeOrStop() {
        if (blecky.settings.listening) startWakeListener() else stopSelf()
    }

    private fun showOnScreen() {
        if (!Settings.canDrawOverlays(this)) return
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_FROM_WAKE, true),
            )
        }
    }

    private fun listeningNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, BleckyApp.CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Blecky is listening")
            .setContentText("Say “Hey Blecky” or “Bleck” any time")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val TAG = "Blecky"
        private const val NOTIFICATION_ID = 1
        private const val RESTART_NOTIFICATION_ID = 2
        private const val SAMPLE_RATE = 16000f
        private const val ACTION_LISTEN = "listen"
        private const val ACTION_TALK = "talk"
        private const val ACTION_SAY = "say"
        private const val ACTION_CHECK_IN = "check_in"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_TEXT = "text"

        @Volatile
        var isRunning = false
            private set

        private fun intent(context: Context, action: String) =
            Intent(context, WakeWordService::class.java).setAction(action)

        fun listen(context: Context) {
            context.startForegroundService(intent(context, ACTION_LISTEN))
        }

        fun talk(context: Context) {
            context.startForegroundService(intent(context, ACTION_TALK))
        }

        /** Speaks [text] if the service is already running. */
        fun say(context: Context, text: String) {
            context.startService(intent(context, ACTION_SAY).putExtra(EXTRA_TEXT, text))
        }

        /** Blecky starts a conversation about [prompt] if the service is already running. */
        fun checkIn(context: Context, prompt: String) {
            context.startService(intent(context, ACTION_CHECK_IN).putExtra(EXTRA_TEXT, prompt))
        }

        fun stop(context: Context) {
            context.blecky.settings.listening = false
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        /** Android only lets the microphone start from the foreground, so ask the user to tap once. */
        fun notifyRestartNeeded(context: Context) {
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = Notification.Builder(context, BleckyApp.CHANNEL_REMINDERS)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Blecky stopped listening")
                .setContentText("Tap to turn “Hey Blecky” back on")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(RESTART_NOTIFICATION_ID, notification)
        }
    }
}

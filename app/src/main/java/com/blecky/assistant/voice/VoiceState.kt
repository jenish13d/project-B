package com.blecky.assistant.voice

/** What Blecky is doing right now, for the particle orb. Written by the voice code, read every frame. */
object VoiceState {
    enum class Mode { OFF, WAITING, LISTENING, THINKING, SPEAKING }

    @Volatile
    var mode = Mode.OFF

    /** Loudness of the mic (while listening) or of Blecky's voice (while speaking), 0 to 1. */
    @Volatile
    var level = 0f
}

package com.blecky.assistant.voice

/**
 * "Blecky" isn't in the offline speech model's dictionary, so the wake grammar listens for the
 * closest words it does know ("bleck", "blackie", "becky", "black").
 */
object WakeWord {
    private val GREETING = "(hey|hi|okay|ok|yo)"
    private val NAME = "(bleck|blackie|becky|black)"
    private val WITH_GREETING = Regex("\\b$GREETING $NAME\\b")

    // Said on its own, "Bleck" (often heard as "black") also wakes Blecky.
    private val NAME_ALONE = Regex("^(bleck|blackie|black)$")

    /** Vosk grammar: only these phrases (or unknown speech) can be recognised while waiting. */
    val GRAMMAR = """["hey bleck", "hey blackie", "hey becky", "hey black", "hi bleck", "hi blackie",
        "okay bleck", "okay blackie", "yo bleck", "bleck", "blackie", "black", "[unk]"]"""

    /**
     * Whether [heard] is a wake phrase. A lone name only counts on a final result, so it isn't
     * mistaken for the start of a longer sentence.
     */
    fun matches(heard: String?, isFinal: Boolean = true): Boolean {
        val raw = heard?.lowercase()?.trim() ?: return false
        if (WITH_GREETING.containsMatchIn(clean(raw))) return true
        // Background noise before the name is fine; more speech after it means a sentence.
        val alone = raw.replace(Regex("^(\\[unk]\\s*)+"), "")
        return isFinal && "[unk]" !in alone && NAME_ALONE.matches(clean(alone))
    }

    private fun clean(text: String) =
        text.replace("[unk]", " ").replace(Regex("[^a-z ]"), " ").replace(Regex(" +"), " ").trim()

    private val YES = Regex("\\b(yes|yeah|yep|yup|sure|ok|okay|send|send it|call|do it|go ahead|confirm|correct|please)\\b")
    private val NO = Regex("\\b(no|nope|don't|do not|cancel|stop|wait|never mind|nevermind)\\b")

    fun isYes(heard: String?): Boolean {
        val text = heard?.lowercase() ?: return false
        return YES.containsMatchIn(text) && !NO.containsMatchIn(text)
    }

    private val GOODBYE = Regex("^(stop|bye|goodbye|bye bye|thanks|thank you|thanks blecky|that's all|that is all|never mind|nevermind|nothing)[.! ]*$")

    fun isGoodbye(heard: String): Boolean = GOODBYE.matches(heard.lowercase().trim())

    private val GREETINGS = listOf("Yes, %s?", "I'm here, %s.", "Go ahead, %s.", "What do you need, %s?", "At your service, %s.")
    private val GREETINGS_NO_NAME = listOf("Yes?", "I'm here.", "Go ahead.", "What do you need?", "At your service.")

    fun greeting(name: String): String =
        if (name.isBlank()) GREETINGS_NO_NAME.random() else GREETINGS.random().format(name)
}

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

    private val GREETINGS = listOf(
        "Yes, %s?", "I'm here.", "Go on, %s.", "Hey %s, what's up?", "Mm-hm?", "Listening.",
        "What's on your mind?", "Talk to me, %s.", "Yep, I'm with you.", "Hey you.", "What do you need?",
        "At your service.", "Right here, %s.", "Hmm?", "Yo, what's up?",
    )
    private val MORNING = listOf("Morning, %s!", "Good morning! What's first?", "Hey, early bird.")
    private val NIGHT = listOf("Still up, %s?", "Hey night owl.", "Late one, huh? What's up?")
    private val GOODBYES = listOf(
        "Anytime.", "Catch you later.", "I'm around if you need me.", "Okay, talk soon.", "Sure thing.",
        "Bye for now!", "Cool, I'll be here.",
    )

    private val recent = ArrayDeque<String>()

    /** A short spoken reply to the wake word that changes every time and suits the hour. */
    fun greeting(name: String, hour: Int = java.time.LocalTime.now().hour): String {
        val pool = GREETINGS + when (hour) {
            in 5..10 -> MORNING
            in 0..3, 23 -> NIGHT
            else -> emptyList()
        }
        return pick(pool).format(name.ifBlank { "" }).replace(Regex(",? +([?!.])"), "$1").replace(" ,", ",").replace(Regex(" {2,}"), " ").trim()
    }

    fun goodbye(): String = pick(GOODBYES)

    /** Random choice that avoids the last few lines said. */
    private fun pick(pool: List<String>): String {
        val choice = pool.filterNot { it in recent }.ifEmpty { pool }.random()
        recent.addLast(choice)
        while (recent.size > 5) recent.removeFirst()
        return choice
    }
}

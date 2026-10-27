package com.blecky.assistant.voice

/**
 * "Blecky" isn't in the offline speech model's dictionary, so the wake grammar listens for the
 * closest words it does know ("blackie", "becky", "black") after a greeting.
 */
object WakeWord {
    private val TRIGGERS = listOf("hey blackie", "hey becky", "hey black", "hi blackie", "okay blackie", "ok blackie")

    /** Vosk grammar: only these phrases (or unknown speech) can be recognised while waiting. */
    val GRAMMAR = """["hey blackie", "hey becky", "hey black", "hi blackie", "okay blackie", "blackie", "[unk]"]"""

    fun matches(heard: String?): Boolean {
        val text = heard?.lowercase()?.replace(Regex("[^a-z ]"), " ")?.replace(Regex(" +"), " ")?.trim() ?: return false
        return TRIGGERS.any { trigger -> Regex("\\b$trigger\\b").containsMatchIn(text) }
    }

    private val YES = Regex("\\b(yes|yeah|yep|yup|sure|ok|okay|send|send it|call|do it|go ahead|confirm|correct|please)\\b")
    private val NO = Regex("\\b(no|nope|don't|do not|cancel|stop|wait|never mind|nevermind)\\b")

    fun isYes(heard: String?): Boolean {
        val text = heard?.lowercase() ?: return false
        return YES.containsMatchIn(text) && !NO.containsMatchIn(text)
    }

    private val GOODBYE = Regex("^(stop|bye|goodbye|bye bye|thanks|thank you|thanks blecky|that's all|that is all|never mind|nevermind|nothing)[.! ]*$")

    fun isGoodbye(heard: String): Boolean = GOODBYE.matches(heard.lowercase().trim())
}

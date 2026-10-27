package com.blecky.assistant.voice

import org.json.JSONObject

/**
 * "Blecky" isn't in the offline speech model's dictionary, so the wake grammar listens for the
 * closest words it does know ("bleck", "blackie", "becky", "black").
 */
object WakeWord {
    private const val GREETING = "(hey|hi|okay|ok|yo)"
    private val WITH_GREETING = Regex("\\b$GREETING (bleck|blackie|black)\\b")
    private val NAME_ALONE = Regex("^(bleck|blackie|black)$")
    private val NAME_WORDS = setOf("bleck", "blackie", "black")

    /** How sure the recognizer must be that it heard her name. */
    private const val MIN_NAME_CONFIDENCE = 0.5
    private const val MIN_ALONE_CONFIDENCE = 0.75

    /**
     * Vosk grammar: only these phrases (or unknown speech) can be recognised while waiting.
     * "hey becky" never wakes Blecky; it's a decoy that soaks up "hey buddy" and "hey baby".
     */
    val GRAMMAR = """["hey bleck", "hey blackie", "hey black", "hi bleck", "hi blackie", "okay bleck",
        "okay blackie", "yo bleck", "hey becky", "bleck", "blackie", "black", "[unk]"]"""

    data class Word(val word: String, val confidence: Double)

    /** Reads the words and confidences out of a Vosk final result. */
    fun words(result: JSONObject): List<Word> {
        val array = result.optJSONArray("result") ?: return result.optString("text").split(' ')
            .filter { it.isNotBlank() }.map { Word(it, 1.0) }
        return (0 until array.length()).map { array.getJSONObject(it) }.map { Word(it.optString("word"), it.optDouble("conf", 0.0)) }
    }

    /**
     * Whether a finished phrase is a confident wake phrase. "Bleck" on its own only counts when
     * [allowNameAlone] is on, because short sounds get mistaken for it.
     */
    fun matches(words: List<Word>, allowNameAlone: Boolean): Boolean {
        val text = clean(words.joinToString(" ") { it.word })
        val nameConfidence = words.filter { it.word in NAME_WORDS }.maxOfOrNull { it.confidence } ?: 0.0
        if (WITH_GREETING.containsMatchIn(text)) return nameConfidence >= MIN_NAME_CONFIDENCE
        if (!allowNameAlone) return false
        // Noise before the name is fine; more speech after it means a sentence.
        val alone = words.dropWhile { it.word == "[unk]" }
        return alone.none { it.word == "[unk]" } &&
            NAME_ALONE.matches(clean(alone.joinToString(" ") { it.word })) &&
            nameConfidence >= MIN_ALONE_CONFIDENCE
    }

    /** Convenience for plain text (every word fully confident). */
    fun matches(heard: String?, allowNameAlone: Boolean = true): Boolean =
        matches(heard?.lowercase()?.split(' ')?.filter { it.isNotBlank() }?.map { Word(it, 1.0) } ?: emptyList(), allowNameAlone)

    private fun clean(text: String) =
        text.lowercase().replace("[unk]", " ").replace(Regex("[^a-z ]"), " ").replace(Regex(" +"), " ").trim()

    private val YES = Regex("\\b(yes|yeah|yep|yup|sure|ok|okay|send|send it|call|do it|go ahead|confirm|correct|please)\\b")
    private val NO = Regex("\\b(no|nope|don't|do not|cancel|stop|wait|never mind|nevermind)\\b")

    fun isYes(heard: String?): Boolean {
        val text = heard?.lowercase() ?: return false
        return YES.containsMatchIn(text) && !NO.containsMatchIn(text)
    }

    private val GOODBYE_START = Regex(
        "^(thanks|thank you|thank u|thx|bye|goodbye|ok bye|okay bye|good night|see you|that's all|that is all|" +
            "that's it|never mind|nevermind|cool thanks|धन्यवाद|शुक्रिया|थैंक्स|थैंक यू|बाय|આભાર|ધન્યવાદ|બાય)(\\s|$)",
    )
    private val GOODBYE_EXACT = Regex("^(stop|nothing|no thanks|बस|बस ठीक है|बस इतना ही|ठीक है बाय|બસ|બસ આભાર|ચાલો બાય)$")

    /** "Thanks", "ok bye", "धन्यवाद", "આભાર": a short sign-off ends the conversation; "thanks, now call mom" doesn't. */
    fun isGoodbye(heard: String): Boolean {
        val text = heard.lowercase().replace(Regex("[.,!?।]"), " ").replace(Regex("\\s+"), " ").trim()
        if (text.split(' ').size > 5) return false
        return GOODBYE_START.containsMatchIn(text) || GOODBYE_EXACT.matches(text)
    }

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

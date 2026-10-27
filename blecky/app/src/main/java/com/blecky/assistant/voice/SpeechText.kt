package com.blecky.assistant.voice

/** Turns a chat reply into something that sounds right when read aloud. */
object SpeechText {
    private val URL = Regex("https?://\\S+")
    private val MARKDOWN = Regex("[*_#`>|~]+")
    private val BULLET = Regex("(?m)^\\s*([-•]|\\d+\\.)\\s+")
    private val SPACES = Regex("\\s+")

    enum class Script { LATIN, DEVANAGARI, GUJARATI }

    /** Which writing system most of the letters use, to pick a voice that can read it. */
    fun script(text: String): Script {
        var devanagari = 0
        var gujarati = 0
        var latin = 0
        text.forEach { c ->
            when (c) {
                in '\u0900'..'\u097F' -> devanagari++
                in '\u0A80'..'\u0AFF' -> gujarati++
                in 'a'..'z', in 'A'..'Z' -> latin++
            }
        }
        return when {
            gujarati > 0 && gujarati >= devanagari && gujarati * 2 >= latin -> Script.GUJARATI
            devanagari > 0 && devanagari * 2 >= latin -> Script.DEVANAGARI
            else -> Script.LATIN
        }
    }

    fun clean(text: String): String {
        val noEmoji = buildString {
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val emoji = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp == 0xFE0F || cp == 0x200D
                if (!emoji) appendCodePoint(cp)
                i += Character.charCount(cp)
            }
        }
        return noEmoji
            .replace(URL, "the link")
            .replace(BULLET, "")
            .replace(MARKDOWN, "")
            .replace(SPACES, " ")
            .trim()
    }
}

package com.blecky.assistant.voice

/** Turns a chat reply into something that sounds right when read aloud. */
object SpeechText {
    private val URL = Regex("https?://\\S+")
    private val MARKDOWN = Regex("[*_#`>|~]+")
    private val BULLET = Regex("(?m)^\\s*([-•]|\\d+\\.)\\s+")
    private val SPACES = Regex("\\s+")

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

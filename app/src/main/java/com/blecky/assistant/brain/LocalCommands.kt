package com.blecky.assistant.brain

/**
 * Everyday commands understood instantly on the phone, with no AI and no internet: calls, opening
 * apps, timers, the torch, the time and the battery. English, Hinglish, Hindi and Gujarati.
 */
object LocalCommands {
    /** A recognised command: which tool to run with which arguments ("time" is answered directly). */
    data class Command(val tool: String, val args: Map<String, Any?> = emptyMap())

    private val SIM = "(jio|airtel|vi|vodafone|idea|bsnl|sim ?[12]|first sim|second sim)"

    // Checked in order; opening apps comes last so "open" never swallows a more specific command.
    private val patterns: List<Pair<Regex, (MatchResult) -> Command?>> = listOf(
        // Calls
        Regex("^(?:please )?(?:call|phone|dial|ring) (.+?)(?: (?:on|from|with|using|via) $SIM(?: sim)?)?$") to { m ->
            Command("call", mapOf("contact" to m.groupValues[1], "sim" to m.groupValues[2].ifBlank { null }))
        },
        Regex("^(.+?) (?:ko|nu|ne) (?:call|phone|fone)(?: (?:karo|kar do|kar|lagao|laga do|kari do|karje|joine))?(?: $SIM (?:se|thi))?$") to { m ->
            Command("call", mapOf("contact" to m.groupValues[1], "sim" to m.groupValues[2].ifBlank { null }))
        },
        Regex("^(.+?) को (?:कॉल|फ़ोन|फोन) (?:करो|कर दो|लगाओ)$") to { m -> Command("call", mapOf("contact" to m.groupValues[1])) },
        Regex("^(.+?)(?:ને| ને) (?:કૉલ|કોલ|ફોન) (?:કર|કરો|કરી દે|લગાડ)$") to { m -> Command("call", mapOf("contact" to m.groupValues[1])) },

        // Timers
        Regex("^(?:set |start )?(?:a )?timer (?:for )?(\\d+) ?(seconds?|secs?|minutes?|mins?|hours?|hrs?)$") to { m -> timer(m.groupValues[1], m.groupValues[2]) },
        Regex("^(?:set |start )?(?:a )?(\\d+) ?(seconds?|secs?|minutes?|mins?|hours?|hrs?)(?: ka| ki| nu| no)? timer(?: (?:set )?(?:karo|lagao|laga do|kar|set kar))?$") to { m ->
            timer(m.groupValues[1], m.groupValues[2])
        },

        // Torch
        Regex("^(?:turn |switch )?(on|off) (?:the )?(?:flashlight|torch|flash ?light)$") to { m -> torch(m.groupValues[1] == "on") },
        Regex("^(?:turn |switch )?(?:the )?(?:flashlight|torch|flash ?light) (on|off)$") to { m -> torch(m.groupValues[1] == "on") },
        Regex("^(?:torch|flashlight|light) (?:jalao|chalu karo|on karo|chalu kar|on kar)$") to { torch(true) },
        Regex("^(?:torch|flashlight|light) (?:bujhao|band karo|off karo|band kar|off kar)$") to { torch(false) },
        Regex("^(?:टॉर्च|लाइट) (?:जलाओ|चालू करो|ऑन करो)$") to { torch(true) },
        Regex("^(?:टॉर्च|लाइट) (?:बंद करो|बुझाओ|ऑफ करो)$") to { torch(false) },

        // Time and battery
        Regex("^(?:what(?:'s| is) the time|what time is it|time (?:kya|shu) (?:hai|thayo|che)|kitne baje (?:hai|hain)|ketla vagya)$") to { Command("time") },
        Regex("^(?:कितने बजे (?:है|हैं)|समय क्या है|ટાઇમ શું થયો|કેટલા વાગ્યા)$") to { Command("time") },
        Regex("^(?:how much battery|battery(?: level| status)?|how(?:'s| is) (?:my |the )?battery|battery kitni hai|kitni battery hai)$") to {
            Command("device_status")
        },
        // Opening apps
        Regex("^(?:please )?(?:open|launch) (?:the )?(.+?)(?: app)?$") to { m -> Command("open_app", mapOf("app_name" to m.groupValues[1])) },
        Regex("^(.+?) (?:kholo|khol do|open karo|open kar|kholi do|kholo na)$") to { m -> Command("open_app", mapOf("app_name" to m.groupValues[1])) },
        Regex("^(.+?) (?:खोलो|खोल दो|ओपन करो)$") to { m -> Command("open_app", mapOf("app_name" to m.groupValues[1])) },
        Regex("^(.+?) (?:ખોલ|ખોલો|ખોલી દે|ઓપન કર)$") to { m -> Command("open_app", mapOf("app_name" to m.groupValues[1])) },
    )

    /** The command in [heard], if it is one of the everyday ones. */
    fun parse(heard: String): Command? {
        val text = heard.lowercase()
            .replace(Regex("[.,!?।]"), " ")
            .replace(Regex("^(?:hey |ok |okay )?(?:blecky|bleck|blackie)\\s+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        for ((regex, build) in patterns) {
            val match = regex.matchEntire(text) ?: continue
            val command = build(match) ?: continue
            // A "name" of five or more words is a sentence ("call me crazy but..."), not a contact.
            val name = (command.args["contact"] ?: command.args["app_name"]) as? String
            if (name != null && name.split(' ').size > 4) return null
            return command
        }
        return null
    }

    private fun timer(amount: String, unit: String): Command? {
        val n = amount.toIntOrNull() ?: return null
        val seconds = when {
            unit.startsWith("h") -> n * 3600
            unit.startsWith("m") -> n * 60
            else -> n
        }
        return Command("set_timer", mapOf("seconds" to seconds))
    }

    private fun torch(on: Boolean) = Command("flashlight", mapOf("on" to on))
}

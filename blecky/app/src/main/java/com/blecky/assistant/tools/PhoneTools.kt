package com.blecky.assistant.tools

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SmsManager

fun phoneTools(): List<BleckyTool> = listOf(
    OpenApp, CallContact, SendText, SetTimer, SetAlarm, Flashlight, Navigate, OpenWebsite, DeviceStatus,
)

/** Starts an activity from Blecky (which may be running in the background) and reports lock state. */
internal fun Context.launch(intent: Intent, done: String): String {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
    val notes = buildList {
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) add("The phone is locked.")
        if (!Settings.canDrawOverlays(this@launch)) {
            add("Blecky may be blocked from opening screens in the background; if nothing appeared, tap step 2 in the Blecky app.")
        }
    }
    return (listOf(done) + notes).joinToString(" ")
}

data class Contact(val name: String, val value: String)

/** Finds phone numbers (or emails) of contacts whose name contains [query]. */
internal fun Context.findContacts(query: String, email: Boolean = false): List<Contact> {
    requirePermission(Manifest.permission.READ_CONTACTS, "read your contacts")
    val uri = if (email) ContactsContract.CommonDataKinds.Email.CONTENT_URI else ContactsContract.CommonDataKinds.Phone.CONTENT_URI
    val nameCol = ContactsContract.Contacts.DISPLAY_NAME
    val valueCol = if (email) ContactsContract.CommonDataKinds.Email.ADDRESS else ContactsContract.CommonDataKinds.Phone.NUMBER
    val found = linkedMapOf<String, Contact>()
    contentResolver.query(uri, arrayOf(nameCol, valueCol), "$nameCol LIKE ?", arrayOf("%$query%"), "$nameCol ASC")?.use { c ->
        while (c.moveToNext()) {
            val name = c.getString(0) ?: continue
            val value = c.getString(1) ?: continue
            found.putIfAbsent("$name|${value.filter { it.isLetterOrDigit() || it == '@' }}", Contact(name, value))
        }
    }
    val all = found.values.toList()
    // Prefer exact name matches ("Mom" over "Mom's office").
    if (all.isNotEmpty()) return all.filter { it.name.equals(query, ignoreCase = true) }.ifEmpty { all }

    // Nothing contains the words, so the recognizer may have misheard the name ("jash" for "Jayesh").
    val everyone = mutableListOf<Contact>()
    contentResolver.query(uri, arrayOf(nameCol, valueCol), null, null, null)?.use { c ->
        while (c.moveToNext()) {
            val name = c.getString(0) ?: continue
            everyone += Contact(name, c.getString(1) ?: continue)
        }
    }
    return closestContacts(query, everyone)
}

/** Contacts whose names sound like [query], best first; a clear winner is returned alone. */
internal fun closestContacts(query: String, contacts: List<Contact>): List<Contact> {
    val scored = contacts.map { it to NameMatch.score(query, it.name) }
        .filter { it.second >= 0.6 }
        .sortedByDescending { it.second }
    if (scored.isEmpty()) return emptyList()
    val best = scored.first()
    val runnerUp = scored.firstOrNull { it.first.name != best.first.name }
    return if (runnerUp == null || best.second - runnerUp.second >= 0.1) {
        listOf(best.first)
    } else {
        scored.map { it.first }.distinctBy { it.name }.take(5)
    }
}

/** Fuzzy name matching that copes with speech-recognition spellings. */
internal object NameMatch {
    fun score(query: String, name: String): Double {
        val q = clean(query)
        val n = clean(name)
        if (q.isEmpty() || n.isEmpty()) return 0.0
        val words = n.split(' ')
        val spelling = (listOf(n) + words).maxOf { similarity(q, it) }
        val sound = if (words.any { skeleton(it) == skeleton(q) && skeleton(q).length >= 2 }) 0.85 else 0.0
        return maxOf(spelling, sound)
    }

    private fun clean(s: String) = s.lowercase().replace(Regex("[^a-z ]"), "").replace(Regex(" +"), " ").trim()

    /** First letter plus the consonants that carry the sound: "jayesh" and "jash" both become "js". */
    private fun skeleton(word: String): String {
        if (word.isEmpty()) return ""
        val rest = word.drop(1).filter { it !in "aeiouyhw" }
        return (word.first() + rest).fold("") { acc, c -> if (acc.lastOrNull() == c) acc else acc + c }
    }

    private fun similarity(a: String, b: String): Double {
        val longest = maxOf(a.length, b.length)
        return if (longest == 0) 1.0 else 1.0 - levenshtein(a, b).toDouble() / longest
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }
}

/** Picks the SIM the user named ("Jio", "Airtel", "SIM 2", "second") from the phone's SIM labels. */
internal fun pickSim(labels: List<String>, wanted: String): Int? {
    val w = wanted.lowercase().trim()
    labels.indexOfFirst { label ->
        val l = label.lowercase()
        val brand = l.split(' ').first()
        l.isNotBlank() && (l.contains(w) || (brand !in setOf("sim", "card") && w.contains(brand)))
    }.takeIf { it >= 0 }?.let { return it }
    val position = Regex("\\d").find(w)?.value?.toIntOrNull()
        ?: when {
            "first" in w -> 1
            "second" in w -> 2
            else -> null
        }
    return position?.minus(1)?.takeIf { it in labels.indices }
}

@SuppressLint("MissingPermission") // checked by requirePermission below
internal fun Context.simAccount(wanted: String?): Pair<PhoneAccountHandle, String>? {
    if (wanted == null) return null
    requirePermission(Manifest.permission.READ_PHONE_STATE, "choose which SIM to call from")
    val telecom = getSystemService(TelecomManager::class.java)
    val accounts = telecom.callCapablePhoneAccounts.map { it to (telecom.getPhoneAccount(it)?.label?.toString() ?: "") }
    val index = pickSim(accounts.map { it.second }, wanted)
        ?: throw ToolError("There's no SIM called '$wanted'. This phone has: ${accounts.joinToString { it.second.ifBlank { "unnamed SIM" } }}.")
    return accounts[index].first to accounts[index].second
}

internal fun looksLikeNumber(text: String) = text.count { it.isDigit() } >= 3 && text.all { it.isDigit() || it in "+-() ." }

/** Resolves "Mom" or "+1 555 0100" to a single phone contact, or explains why not. */
internal fun Context.resolvePhone(who: String): Contact {
    if (looksLikeNumber(who)) return Contact(who, who)
    val matches = findContacts(who)
    return when {
        matches.isEmpty() -> throw ToolError("No contact named '$who' with a phone number.")
        matches.size > 1 && matches.map { it.name }.distinct().size > 1 ->
            throw ToolError("Several contacts match '$who': ${matches.take(6).joinToString { it.name }}. Ask which one.")
        else -> matches.first()
    }
}

object OpenApp : BleckyTool(
    "open_app", "Open an installed app by its name, e.g. WhatsApp, YouTube, Camera, Settings.",
    listOf(Param("app_name", "string", "The app's name as shown on the home screen")),
) {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        val wanted = args.string("app_name")
        val pm = ctx.context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val match = apps.firstOrNull { it.first.equals(wanted, ignoreCase = true) }
            ?: apps.firstOrNull { it.first.contains(wanted, ignoreCase = true) }
            ?: apps.firstOrNull { wanted.contains(it.first, ignoreCase = true) }
            ?: throw ToolError("No installed app called '$wanted'.")
        val intent = pm.getLaunchIntentForPackage(match.second) ?: throw ToolError("${match.first} can't be opened.")
        return ctx.context.launch(intent, "Opened ${match.first}.")
    }
}

object CallContact : BleckyTool(
    "call", "Phone call a contact by name, or a phone number. Names can be misheard, so the closest match is used.",
    listOf(
        Param("contact", "string", "Contact name or phone number"),
        Param("sim", "string", "Which SIM to call from, if the user said, e.g. 'Jio', 'Airtel', 'SIM 2'", required = false),
    ),
) {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        ctx.context.requirePermission(Manifest.permission.CALL_PHONE, "make phone calls")
        val contact = ctx.context.resolvePhone(args.string("contact"))
        val sim = ctx.context.simAccount(args.optString("sim"))
        val via = sim?.let { " on ${it.second}" } ?: ""
        if (!ctx.user.confirm("Call ${contact.name}$via?")) return "The user cancelled; no call was made."
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(contact.value)}"))
        sim?.let { intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it.first) }
        return ctx.context.launch(intent, "Calling ${contact.name}$via.")
    }
}

object SendText : BleckyTool(
    "send_text", "Send an SMS text message to a contact or phone number. The app asks the user to confirm.",
    listOf(
        Param("contact", "string", "Contact name or phone number"),
        Param("message", "string", "The exact message text to send"),
    ),
) {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        ctx.context.requirePermission(Manifest.permission.SEND_SMS, "send text messages")
        val contact = ctx.context.resolvePhone(args.string("contact"))
        val message = args.string("message")
        if (!ctx.user.confirm("Text ${contact.name}: $message. Send it?")) return "The user cancelled; nothing was sent."
        val sms = ctx.context.getSystemService(SmsManager::class.java)
        sms.sendMultipartTextMessage(contact.value, null, sms.divideMessage(message), null, null)
        return "Text sent to ${contact.name}."
    }
}

object SetTimer : BleckyTool(
    "set_timer", "Start a countdown timer in the clock app.",
    listOf(
        Param("seconds", "integer", "Timer length in seconds"),
        Param("label", "string", "Optional label", required = false),
    ),
) {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        val seconds = args.int("seconds")
        if (seconds !in 1..86_400) throw ToolError("Timers can be 1 second to 24 hours.")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        args.optString("label")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return ctx.context.launch(intent, "Timer set for $seconds seconds.")
    }
}

object SetAlarm : BleckyTool(
    "set_alarm", "Set an alarm in the clock app for the next occurrence of a time of day.",
    listOf(
        Param("hour", "integer", "Hour, 0-23"),
        Param("minute", "integer", "Minute, 0-59"),
        Param("label", "string", "Optional label", required = false),
    ),
) {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        val hour = args.int("hour")
        val minute = args.int("minute")
        if (hour !in 0..23 || minute !in 0..59) throw ToolError("That isn't a valid time.")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        args.optString("label")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return ctx.context.launch(intent, "Alarm set for %02d:%02d.".format(hour, minute))
    }
}

object Flashlight : BleckyTool(
    "flashlight", "Turn the phone's flashlight on or off.",
    listOf(Param("on", "boolean", "true to turn on, false to turn off")),
) {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        val on = args.optBool("on") ?: throw ToolError("Say whether to turn it on or off.")
        val camera = ctx.context.getSystemService(CameraManager::class.java)
        val id = camera.cameraIdList.firstOrNull {
            camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: throw ToolError("This phone has no flashlight.")
        camera.setTorchMode(id, on)
        return if (on) "Flashlight on." else "Flashlight off."
    }
}

object Navigate : BleckyTool(
    "navigate", "Start turn-by-turn directions to a place in Google Maps.",
    listOf(Param("destination", "string", "Address or place name")),
) {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        val destination = args.string("destination")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}"))
        return ctx.context.launch(intent, "Starting directions to $destination.")
    }
}

object OpenWebsite : BleckyTool(
    "open_website", "Open a web page in the browser.",
    listOf(Param("url", "string", "Full https URL")),
) {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        val url = args.string("url").let { if (it.startsWith("http")) it else "https://$it" }
        return ctx.context.launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Opened $url.")
    }
}

object DeviceStatus : BleckyTool("device_status", "Get battery level, charging state and whether the phone is locked.") {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        val battery = ctx.context.getSystemService(BatteryManager::class.java)
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = battery.isCharging
        val locked = ctx.context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        return "Battery $level%${if (charging) ", charging" else ""}. Phone is ${if (locked) "locked" else "unlocked"}."
    }
}

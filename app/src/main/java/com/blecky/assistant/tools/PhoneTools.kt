package com.blecky.assistant.tools

import android.Manifest
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
    return all.filter { it.name.equals(query, ignoreCase = true) }.ifEmpty { all }
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
    "call", "Phone call a contact by name, or a phone number.",
    listOf(Param("contact", "string", "Contact name or phone number")),
) {
    override suspend fun run(args: Args, ctx: ToolContext): String {
        ctx.context.requirePermission(Manifest.permission.CALL_PHONE, "make phone calls")
        val contact = ctx.context.resolvePhone(args.string("contact"))
        if (!ctx.user.confirm("Call ${contact.name}?")) return "The user cancelled; no call was made."
        return ctx.context.launch(Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(contact.value)}")), "Calling ${contact.name}.")
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

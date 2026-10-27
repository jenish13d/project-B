# Blecky

Blecky is a personal AI assistant for Android that you talk to. Say **"Hey Blecky"**, wait for the beep,
and ask for something. It works with the screen on or off, as long as listening is switched on.

Blecky runs on Claude (Anthropic) and can:

| Ask for | Example |
|---|---|
| Chat and answers, with live web search | "What's the weather this weekend?" |
| Open apps | "Open WhatsApp" |
| Calls and texts (it asks you to confirm first) | "Call Mom", "Text Sam I'm running late" |
| Reminders (spoken out loud and shown as a notification) | "Remind me to take my pills in 20 minutes" |
| Alarms and timers | "Wake me up at 6:30", "Set a timer for 10 minutes" |
| Notes and to-dos | "Add milk to my notes", "What's on my list?" |
| Calendar (reads and adds to the calendars synced to your phone) | "What's on tomorrow?", "Add dentist Friday at 3" |
| Email: hears new mail from notifications, drafts emails | "Any new emails?", "Email Alex saying the report is done" |
| Flashlight, directions, websites, battery | "Flashlight on", "Navigate to the airport" |

## How "Hey Blecky" works

- A foreground service keeps the microphone open and listens with a small **offline** speech model (Vosk),
  so nothing you say leaves the phone until you have said the wake word.
- "Blecky" isn't in that model's dictionary, so the app listens for the closest sound-alikes it knows
  ("hey blackie", "hey becky"). Say it clearly as one phrase, then speak **after** the beep.
- After the beep, your request goes to Android's speech recognizer, then to Claude, and the answer is
  read aloud. Keep talking to follow up. Stay quiet, or say "thanks" or "bye", to end.
- A notification shows while Blecky is listening, and it has a **Stop** button.

## Setup

1. Install Android Studio, open this folder and press **Run**. Or build from the command line with
   `./gradlew assembleDebug` and install `app/build/outputs/apk/debug/app-debug.apk`. It needs Android 11 or newer.
2. Get an API key at <https://console.anthropic.com> and paste it into Blecky. API usage is billed by Anthropic.
3. Tap steps 1 to 4 in the app to grant access:
   1. Microphone, calls, texts, contacts and calendar.
   2. **Display over other apps**. Without it, Android won't let Blecky open apps or calls while it runs in the background.
   3. **Battery: unrestricted**. Without it, Android may stop Blecky when the screen is off. On Samsung, Xiaomi and
      similar phones, also turn off any "sleeping apps" or "auto-start" restriction for Blecky.
   4. **Notification access**, which lets Blecky read new emails and messages to you.
4. Tap **Start listening**. The first time, Blecky downloads its 40 MB offline listening model.

## Things Android doesn't allow

- **After a restart**, Android won't let an app turn its microphone back on by itself. Blecky shows a
  notification after boot. Tap it once and listening starts again.
- When the phone is **locked**, Blecky can still talk, start calls, send texts and set reminders.
  Apps and email drafts it opens show up after you unlock.
- Blecky has to keep the microphone and CPU awake to hear you, so it uses more battery than usual.
- Email drafts open in your email app and you tap send. Blecky never sends email by itself.

## Code layout

- `Brain.kt` runs the Claude tool-use loop. The default model is `claude-opus-5-5` at low effort, which
  keeps voice replies quick. Refusal fallback is on.
- `tools/` holds everything Blecky can do. To add a skill, write a `BleckyTool` and add it to `allTools()`.
- `voice/WakeWordService.kt` is the always-on listener and the spoken conversation loop.
- `voice/VoiceIO.kt` handles speech recognition and text-to-speech.

Run the unit tests with `./gradlew testDebugUnitTest`.

## Privacy

Your API key, notes and reminders are stored only in the app's private storage on the phone. Audio
before the wake word is processed on the phone. What you say after the beep goes through Android's
speech recognizer (usually Google) and Anthropic's API.

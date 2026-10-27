# Blecky

Blecky is a personal AI assistant for Android with a personality in the spirit of JARVIS and FRIDAY:
quick, warm, a little witty, and on your side. Say **"Hey Blecky"** or just **"Bleck"**. She answers
("Yes, Jai?") and you ask for something. It works with the screen on or off, as long as listening is
switched on. With the voice lock on, she only wakes for your voice.

She talks with a **natural human voice**: Kokoro, a neural voice that runs offline on the phone, or
ElevenLabs if you add a key. She keeps the conversation going without you saying her name every time,
and the screen is a HUD-style particle orb that reacts as she listens, thinks and speaks.

She also talks first. She does a daily check-in (9:00 by default), and when you mention something
that matters, like an exam or an interview, she schedules a follow-up and asks you how it went.
She remembers things about you between conversations.

Blecky runs on Claude (Anthropic) and can:

| Ask for | Example |
|---|---|
| Chat and answers, with live web search | "What's the weather this weekend?" |
| Open apps | "Open WhatsApp" |
| Calls and texts (it asks you to confirm first). Misheard names still match, and you can pick a SIM | "Call Jayesh on Jio", "Text Sam I'm running late" |
| Reminders (spoken out loud and shown as a notification) | "Remind me to take my pills in 20 minutes" |
| Alarms and timers | "Wake me up at 6:30", "Set a timer for 10 minutes" |
| Notes and to-dos | "Add milk to my notes", "What's on my list?" |
| Calendar (reads and adds to the calendars synced to your phone) | "What's on tomorrow?", "Add dentist Friday at 3" |
| Email: hears new mail from notifications, drafts emails | "Any new emails?", "Email Alex saying the report is done" |
| Flashlight, directions, websites, battery | "Flashlight on", "Navigate to the airport" |
| Memory | "My sister's birthday is 3 March", "Forget where I work" |
| Follow-ups she starts herself | "I have an interview tomorrow at 10", and afterwards she asks how it went |

## How "Hey Blecky" works

- A foreground service keeps the microphone open and listens with a small **offline** speech model (Vosk),
  so nothing you say leaves the phone until you have said the wake word.
- "Blecky" isn't in that model's dictionary, so the app listens for "Hey Bleck", "Hey Blackie" and
  "Hey Becky", or "Bleck" said on its own followed by a pause. Wait for her reply before you ask.
- **Voice lock:** in Settings, tap *Teach Blecky my voice* and say "Hey Blecky" five times. From then on,
  each wake phrase is compared with your voiceprint, on the phone. If she ignores you, set *Voice match*
  to relaxed. If she wakes for other people, set it to strict. The button shows how well your last try matched.
  The lock checks the wake phrase only. After she wakes, whoever is talking is heard.
- After she answers, your request goes to Android's speech recognizer, then to Claude, and the reply is
  read aloud. **Just keep talking. You don't need to say her name again.** She keeps listening for
  45 seconds after each reply. Change this in Settings, all the way up to "until I say bye". Say
  "thanks" or "bye" to end.
- You can also tap the orb to talk, or long-press it to make her stop talking.
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
   4. **Notification access**, which lets Blecky read new emails and messages to you. Android blocks this
      for apps installed from an APK file. If it says "restricted setting" or "not allowed", open
      **Settings → Apps → Blecky**, tap **⋮** (top right) → **Allow restricted settings**, then try again.
      The ⋮ option only appears after Android has blocked it once. The app walks you through this.
4. Tap **Start listening**. The first time, Blecky downloads two offline models (about 55 MB in total).
5. Under **Settings → Voice**, tap **Download natural human voice** (about 100 MB). Without it she
   uses the phone's robotic voice. For the most lifelike voice, paste an ElevenLabs API key (free tier
   at elevenlabs.io).
6. Optional, under **Settings**: teach her your voice and turn on the voice lock, try other voices
   (she starts on a friendly female English voice, and the choice depends on the voices your phone
   has installed), set or turn off the daily check-in, and see or clear what she remembers.

## Troubleshooting

- **"Anthropic rejected that request (error 400)" or "out of credit"**: the API key works, but the
  Anthropic account has no credit. Add some at console.anthropic.com → Billing. Blecky now shows
  Anthropic's exact message.
- **"Can't reach Anthropic"**: check mobile data or Wi-Fi, and make sure no data saver or VPN is blocking Blecky.
- **Voice pauses between sentences**: the offline voice is slower on older phones. Try ElevenLabs.

## Things Android doesn't allow

- **After a restart**, Android won't let an app turn its microphone back on by itself. Blecky shows a
  notification after boot. Tap it once and listening starts again.
- When the phone is **locked**, Blecky can still talk, start calls, send texts and set reminders.
  Apps and email drafts it opens show up after you unlock.
- Blecky has to keep the microphone and CPU awake to hear you, so it uses more battery than usual.
- Email drafts open in your email app and you tap send. Blecky never sends email by itself.
- Voice matching runs offline and is good, not bank-grade. Someone with a similar voice, or a
  recording of you, may get through. Don't rely on it as security.
- Check-ins are spoken only while listening is on. Otherwise they appear as a notification.

## Code layout

- `Brain.kt` runs the Claude tool-use loop. The default model is `claude-opus-5-5` at low effort, which
  keeps voice replies quick. Refusal fallback is on.
- `tools/` holds everything Blecky can do. To add a skill, write a `BleckyTool` and add it to `allTools()`.
- `voice/WakeWordService.kt` is the always-on listener, the voice lock and the spoken conversation loop.
- `voice/VoiceEnrollment.kt` and `voice/SpeakerProfile.kt` record and compare your voiceprint.
- `tools/MemoryTools.kt` holds long-term memory. `tools/Reminders.kt` holds reminders and the check-ins Blecky starts.
- `voice/VoiceIO.kt` handles speech recognition and picks the voice. `voice/KokoroVoice.kt`, `voice/ElevenLabsVoice.kt` and `voice/PcmPlayer.kt` produce the speech.
- `ui/ParticleOrbView.kt` draws the particle orb. `MainActivity` is the chat screen and `SettingsActivity` holds the setup.

Run the unit tests with `./gradlew testDebugUnitTest`.

## Built with open-source projects

- [Vosk](https://github.com/alphacep/vosk-api): offline wake-word spotting and voice matching.
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx): runs neural voices on the phone.
- [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M): the natural female voices (Apache 2.0).
- [Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java): Claude, Blecky's brain.

The sherpa-onnx library is downloaded from its GitHub release, and its checksum verified, on the first build.

## Privacy

Your API key, notes, reminders, memories and voiceprint are stored only in the app's private storage on the phone. Audio
before the wake word is processed on the phone. What you say after she wakes goes through Android's
speech recognizer (usually Google) and Anthropic's API. With an ElevenLabs key, Blecky's replies are sent to
ElevenLabs to be spoken. The Kokoro voice runs entirely on the phone.

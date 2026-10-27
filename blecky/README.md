# Blecky

Blecky is a personal AI assistant for Android with a personality in the spirit of JARVIS and FRIDAY:
quick, warm, a little witty, and on your side. Say **"Hey Blecky"** or just **"Bleck"**. She answers
("Yes, Jai?") and you ask for something. It works with the screen on or off, as long as listening is
switched on. With the voice lock on, she only wakes for your voice.

**She also thinks without internet or any key.** Download a free on-phone AI (Gemma 4 by Google,
Qwen3 or LFM2), and it runs entirely on your phone. In Auto mode she uses it when you're offline or
every free online limit is used up. Calls, timers, the torch, opening apps, the time and the battery
work instantly with no AI at all, in English, Hinglish, Hindi and Gujarati.

**She has an inner life.** Her mood shifts with what happens and fades back over half a day. She
remembers how you were feeling last time and keeps a short diary of moments you shared, so she can
ask how the exam went or be happy for you days later. It's simulated, and if you ask, she's honest
that she's an AI.

She understands **English, Hindi and Gujarati**, mixed too, and answers in the same language.

**No keys, no accounts, no bills.** Tap **Settings → Set up everything** once, on Wi-Fi. Blecky then
runs on open-source AI from GitHub and Hugging Face, all on the phone:

| Step | What runs it |
|---|---|
| Hearing "Hey Blecky", and checking it's your voice | Vosk, offline |
| Understanding your words | Android's built-in speech recognizer |
| Everyday commands (call, timer, torch, apps, time, battery) | Built-in rules, instant |
| Thinking and talking | Gemma 4 E2B by Google, on the phone |
| Fresh facts when you're online | Google News, Wikipedia and Open-Meteo, free and keyless |
| Memory, moods and diary | On the phone |
| Her voice | Supertonic 3 (MIT license), on the phone, English and Hindi |

A phone can only run one AI model at a time, so these are steps in a pipeline, not a thousand brains
at once. If you add free online keys (optional: Gemini, Groq, Cerebras, OpenRouter), she uses them for
harder questions when you're online. Claude is a paid option.

The screen is a HUD-style particle orb that reacts as she listens, thinks and speaks, and she keeps
the conversation going without you saying her name each time.

She also talks first. She does a daily check-in (9:00 by default), and when you mention something
that matters, like an exam or an interview, she schedules a follow-up and asks you how it went.
She remembers things about you between conversations.

Blecky can:

| Ask for | Example |
|---|---|
| Chat and answers, with live news search (Google News and Wikipedia) | "What happened in the match today?" |
| Weather (Open-Meteo) | "Do I need an umbrella tomorrow?" |
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
- "Blecky" isn't in that model's dictionary, so the app listens for "Hey Blecky" or "Hey Bleck" (also
  "Hi" or "Okay" plus her name). She wakes only when the recognizer is confident it heard her name,
  and she ignores the mic for two seconds after she finishes talking, so she doesn't hear her own echo.
  "Bleck" said on its own is off by default because short sounds get mistaken for it; turn it on in
  Settings if you want it. Wait for her reply before you ask.
- **Voice lock:** in Settings, tap *Teach Blecky my voice* and say "Hey Blecky" five times. From then on,
  each wake phrase is compared with your voiceprint, on the phone. If she ignores you, set *Voice match*
  to relaxed. If she wakes for other people, set it to strict. The button shows how well your last try matched.
  The lock checks the wake phrase only. After she wakes, whoever is talking is heard.
- After she answers, your request goes to Android's speech recognizer, then to her brain, and the reply is
  read aloud. **Just keep talking. You don't need to say her name again.** She keeps listening for
  45 seconds after each reply. Change this in Settings, all the way up to "until I say bye". Say
  "thanks" or "bye" to end.
- You can also tap the orb to talk, or long-press it to make her stop talking.
- A notification shows while Blecky is listening, and it has a **Stop** button.

## Setup

1. Install Android Studio, open this folder and press **Run**. Or build from the command line with
   `./gradlew assembleDebug` and install `app/build/outputs/apk/debug/app-debug.apk`. It needs Android 11 or newer.
2. Open **Settings** and tap **Set up everything** on Wi-Fi. It downloads her ears (55 MB), her voice
   (129 MB) and the on-phone brain that fits your phone (Gemma 4 E2B, 2.6 GB, on most phones), then
   starts listening. No accounts or keys.
3. Tap steps 1 to 4 in the app to grant access:
   1. Microphone, calls, texts, contacts and calendar.
   2. **Display over other apps**. Without it, Android won't let Blecky open apps or calls while it runs in the background.
   3. **Battery: unrestricted**. Without it, Android may stop Blecky when the screen is off. On Samsung, Xiaomi and
      similar phones, also turn off any "sleeping apps" or "auto-start" restriction for Blecky.
   4. **Notification access**, which lets Blecky read new emails and messages to you. Android blocks this
      for apps installed from an APK file. If it says "restricted setting" or "not allowed", open
      **Settings → Apps → Blecky**, tap **⋮** (top right) → **Allow restricted settings**, then try again.
      The ⋮ option only appears after Android has blocked it once. The app walks you through this.
4. To pick a different voice, go to **Settings → Voice**. Supertonic Zoe (the default), Maya, Ava, Lily and Iris are
   natural and fast on budget phones, and speak Hindi too. Jenny, Amy and Hannah (Piper) are a little
   more robotic. Bella, Sky and Emma (Kokoro) sound very human but are too slow for budget phones.
5. Optional, under **Settings**: online AI keys, then teach her your voice and turn on the voice lock, try other voices
   (she starts on a friendly female English voice, and the choice depends on the voices your phone
   has installed), set or turn off the daily check-in, and see or clear what she remembers.

## On-phone AI

**Settings → On-phone AI** lists the models; the one that fits your phone's memory is marked ★.
Downloads use Android's download manager, so you can leave the app while they run.

| Model | Size | Notes from testing |
|---|---|---|
| Gemma 4 E2B (Google) ★ | 2.6 GB | Best by far: natural, warm, good Hindi and Gujarati, uses tools. About 1.5 GB of RAM. |
| Qwen3 1.7B (Alibaba) | 1 GB | Smaller download; weaker Hindi and Gujarati; slower. |
| LFM2 700M (Liquid AI) | 0.5 GB | Fastest; English only (its Hindi and Gujarati are garbled). |

These are much smaller than the online brains: fine for chatting and quick help, weaker at complex
questions, and they sometimes claim to have done something they didn't. Everyday commands skip the
model entirely, which covers most of that.

**Research step:** when you're online and ask something that needs fresh facts (news, scores, prices,
weather, "who is..."), Blecky looks it up first, for free and with no key, and hands the results to
the model with your question. In testing, Gemma 4 answered from live cricket headlines and gave a
Gujarati weather answer in 2-3 seconds on a server CPU; expect several seconds on a budget phone.

## Languages

Under **Settings → Listening → I speak**, choose English, Hindi and Gujarati (the default), or just
one of them. On Android 14 and later the recognizer can switch languages mid-sentence. If it keeps
mishearing you, pick the one language you're using. For spoken replies:

- **Hindi**: download *Priyamvada, Hindi* from **Settings → Voice** (free, offline).
- **Gujarati**: tap **Set up the Gujarati voice**. It uses Google's free speech engine.

## Xiaomi / Redmi / POCO phones

HyperOS and MIUI are strict with background apps. The Settings screen shows extra steps on these phones:
turn on **Autostart**, allow **pop-ups while running in the background** and **show on lock screen**,
and lock Blecky in Recents (long-press her card and tap the lock). Without these, she stops hearing
you a few minutes after the screen turns off.

## Troubleshooting

- **"I've used up the free limit"**: free tiers allow a set number of requests per minute and per day.
  Wait a minute, or switch to the other free brain in Settings.
- **Error 400 with Claude**: the Anthropic account has no credit. Switch the brain to Gemini, which is free.
- **"Can't reach Anthropic"**: check mobile data or Wi-Fi, and make sure no data saver or VPN is blocking Blecky.
- **Voice pauses between sentences**: pick a "fast" voice (Jenny, Amy or Hannah). The premium voices are too slow for budget phones.

## Battery and heat

- The wake-word listener checks how loud the microphone is first and only runs speech recognition
  when someone is talking. In testing it processed 22% of the audio with "Hey Blecky" still recognized
  at full confidence. In a quiet room it does almost nothing.
- The particle orb draws its 650 particles in 6 batched calls, runs at 30 fps (15 when idle), and
  stops whenever it isn't on screen.
- The on-phone AI (about 1.5 GB for Gemma) is freed after 3 idle minutes, and the voice after 10, so
  they don't crowd the phone's memory. The first reply after a break takes a few extra seconds while
  the model loads.

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

- `Brain.kt` routes each message to the chosen AI. `brain/OpenAiEngine.kt` runs the tool loop for Gemini
  and Groq, and picks a current model by itself when model names change. `brain/ClaudeEngine.kt` runs
  Claude. `brain/Persona.kt` holds her personality. `tools/InnerLife.kt` holds her moods and diary.
- `brain/OfflineEngine.kt` and `brain/OfflineModels.kt` hold the on-phone AI. `brain/LocalCommands.kt`
  handles the instant commands.
- `tools/` holds everything Blecky can do. To add a skill, write a `BleckyTool` and add it to `allTools()`.
- `voice/WakeWordService.kt` is the always-on listener, the voice lock and the spoken conversation loop.
- `voice/VoiceEnrollment.kt` and `voice/SpeakerProfile.kt` record and compare your voiceprint.
- `tools/MemoryTools.kt` holds long-term memory. `tools/Reminders.kt` holds reminders and the check-ins Blecky starts.
- `voice/VoiceIO.kt` handles speech recognition and picks the voice. `voice/NeuralVoices.kt` (Supertonic,
  Piper, Kokoro) and `voice/PcmPlayer.kt` produce the speech. `brain/WebLookup.kt` is the research step.
- `ui/ParticleOrbView.kt` draws the particle orb. `MainActivity` is the chat screen and `SettingsActivity` holds the setup.

Run the unit tests with `./gradlew testDebugUnitTest`.

## Built with open-source projects

- [Vosk](https://github.com/alphacep/vosk-api): offline wake-word spotting and voice matching.
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx): runs neural voices on the phone.
- [Supertonic 3](https://github.com/supertone-inc/supertonic): the default voices (MIT license).
- [Piper](https://github.com/rhasspy/piper) voices: Jenny, Amy and Hannah, fast and natural.
- [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M): the premium voices (Apache 2.0).
- [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) runs the on-phone AI, with models from
  [litert-community](https://huggingface.co/litert-community): Gemma 4, Qwen3 and LFM2.
- [Open-Meteo](https://open-meteo.com) for weather, and Google News RSS and the Wikipedia API for search.
- [Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java): Claude, Blecky's brain.

Builds are signed with `app/blecky-debug.keystore`, so a new APK always installs over the old one.
The sherpa-onnx library is downloaded from its GitHub release, and its checksum verified, on the first build.

## Privacy

Your API key, notes, reminders, memories and voiceprint are stored only in the app's private storage on the phone. Audio
before the wake word is processed on the phone. What you say after she wakes goes through Android's
speech recognizer (usually Google). With the on-phone brain, nothing else leaves the phone except the
free lookups (news, Wikipedia, weather) for questions that need them. If you add online AI keys, your
messages go to that provider. Her voice always runs on the phone.

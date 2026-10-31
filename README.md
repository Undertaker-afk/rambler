# Rambler

An Android voice keyboard (IME) modelled on the Pixel 11 "Rambler" dictation feature. Tap Talk,
say your whole thought, tap Done (or just stop talking), and Rambler types **one clean message**
into whatever field is focused (WhatsApp, SMS, ...).

- **Listens to the whole thought first**, then writes it. The raw transcript is never shown or typed.
- **Removes fillers and self-corrections**: "um", restarted sentences collapse to the final version.
- **Drops side remarks**: swearing at traffic, talking to someone nearby.
- **Follows spoken instructions to the keyboard**: "add fitting emojis", "translate to English",
  "make it shorter / more formal". Saying "Rambler" before the instruction makes it unambiguous,
  but isn't required; the model decides what is message and what is a command, and requests aimed
  at the message's recipient ("can you translate the contract...") stay in the message.

## How it works

1. **Listen** - Android's `SpeechRecognizer` (on-device where available) is chained into one
   continuous session until you tap Done or go quiet (`SpeechCapture`).
2. **Write** - the transcript goes to **Liquid AI LFM3-1.7B** (QAD Q4_0, ~1.05 GB) running on the
   phone through llama.cpp via JNI (`LlmCleaner`, `llama_jni.cpp`). One greedy pass with a few-shot
   ChatML prompt, thinking disabled. The static prompt prefix is evaluated once and its model state
   cached, so each request only processes the new speech.
3. **Safety net** - if the model isn't downloaded, fails, or returns empty/oversized output,
   `RuleBasedCleaner` (fillers, stutters, "scratch that" restarts) is used instead. Commands need
   the model.
4. **Insert** - result is committed to the focused field via `InputConnection`.

Everything runs on-device; the only network use is the one-time model download (resumable).

## Build

Requires Android SDK 35, NDK 27.2.12479018, CMake 3.22.1, JDK 17. The first native build fetches
llama.cpp (pinned tag `b12020`; LFM3 needs b11960 or newer) from GitHub via CMake `FetchContent`.

```
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew :app:testDebugUnitTest   # cleaner, command parser, prompt tests
./gradlew :app:assembleDebug       # builds arm64-v8a + x86_64
```

## Setup on the phone

1. Open **Rambler**, grant microphone permission, download the model.
2. Enable the Rambler keyboard, then switch to it (or use the Switch key from another keyboard).
3. In any text field tap Talk, speak, tap Done.

## Notes

- LFM3-1.7B is the smallest LFM3 text model; it is much bigger than the earlier LFM2-350M choice.
  Swap `MODEL_URL` / `MODEL_FILE` in `ModelManager.kt` for a smaller ChatML-style GGUF if needed
  (the prompt format in `LlmCleaner` is LFM's). The model is under Liquid's LFM 1.0 license.
- The arm64 build targets `armv8.2-a+dotprod`; very old phones without it will crash.
- LFM3-Audio-2B exists and could replace the Android speech recognizer, but is not used here.

## Status

Unit tests pass and the JNI file compiles against llama.cpp b12020 headers. The full native build,
on-device speed, and the model's real-world quality (especially command-vs-message judgement and
translation) have **not** been exercised yet.

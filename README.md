# Rambler

An Android voice keyboard (IME). Tap the mic, ramble, and Rambler types **one clean message** into
whatever text field is focused (WhatsApp, SMS, …): no "umm", restarted sentences collapse to the
final version, and side remarks ("come ON, green light!") are dropped.

## How it works

1. **Listen** – Android's `SpeechRecognizer` (on-device where available) is chained into one
   continuous session until you tap *Done* or go quiet (`SpeechCapture`).
2. **Clean** – the raw transcript goes to a tiny on-device LLM, **Liquid AI LFM2-350M** (Q4_K_M,
   ~230 MB) running through llama.cpp via JNI (`LlmCleaner`, `llama_jni.cpp`). Greedy decoding with a
   few-shot ChatML prompt.
3. **Safety net** – if the model isn't downloaded, fails, or returns something empty/oversized,
   `RuleBasedCleaner` (fillers, stutters, "scratch that"/"no wait" restarts) is used instead.
4. **Insert** – result is committed to the focused field via `InputConnection`.

Everything runs on-device; the only network use is the one-time model download.

## Build

Requires Android SDK 35, NDK 27.2.12479018, CMake 3.22.1, JDK 17.
The first native build fetches llama.cpp (pinned tag `b6999`) from GitHub with CMake `FetchContent`.

```
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew :app:testDebugUnitTest   # rule-based cleaner + prompt tests
./gradlew :app:assembleDebug       # builds arm64-v8a + x86_64
```

## Setup on the phone

1. Open **Rambler** → grant microphone permission → *Download model*.
2. *Enable Rambler keyboard*, then *Switch to Rambler* (or use the 🌐 key from another keyboard).
3. In any text field, tap 🎤 Talk, speak, tap ⏹ Done.

## Swapping the model

Change `MODEL_URL` / `MODEL_FILE` in `ModelManager.kt` (any ChatML-style GGUF works; adjust the
template in `LlmCleaner.buildPrompt` for others).

## Status

Unit tests pass. The native llama.cpp build and on-device behaviour (speech chaining, LLM output
quality, latency) have **not** been exercised yet.

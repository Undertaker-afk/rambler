# Rambler

**Rambler is a fork of [FUTO Keyboard](https://github.com/futo-org/android-keyboard)** (itself a fork of
[LatinIME, the Android Open Source Keyboard](https://android.googlesource.com/platform/packages/inputmethods/LatinIME))
that adds Pixel-style "Rambler" dictation: talk freely, and it types one clean message.

> **This is a modified copy of FUTO Keyboard, not the official FUTO Keyboard.** It is not made, endorsed
> or supported by FUTO. All the keyboard, layouts, dictionaries and Whisper voice input are FUTO's work -
> please see the [FUTO Keyboard website](https://keyboard.futo.tech/) and the original repository, and
> support them. The original README is kept in [FUTO-README.md](FUTO-README.md).

## What Rambler adds

When you dictate with the keyboard's built-in voice input, Rambler waits for you to finish and then,
using **Liquid AI LFM3-1.7B** running on the phone (llama.cpp):

- removes "um", "uh" and other fillers,
- keeps only the final version when you restart or correct a sentence,
- drops side remarks (swearing at traffic, talking to someone nearby),
- follows spoken instructions to the keyboard: "add fitting emojis", "translate to English",
  "make it shorter / more formal". Saying "Rambler" first makes a command unambiguous but is not required.

Then it types the result. While Rambler is on, the raw transcript is never written into the field.

Everything runs on-device; the only network use is the one-time model download (resumable).
If the model isn't installed or fails, a simple rule-based cleaner is used and commands are ignored.

### Using it

1. Build/install the app, enable the keyboard, and set up FUTO's voice input (it downloads a Whisper model).
2. Settings > Voice input > **Rambler model** > tap to download the LFM3 model (about 1 GB).
3. **Rambler cleanup** (same page) turns the feature on or off; it is on by default.

### Where the changes are

Kept deliberately small so upstream updates stay easy to merge:

| Change | Location |
|---|---|
| New module: cleaner, prompt, command parser, model download, JNI llama.cpp bridge | `rambler-llm/` (original Rambler code) |
| Hook: clean the transcript before it is committed | `java/src/org/futo/inputmethod/latin/uix/actions/VoiceInputAction.kt` |
| Settings: toggle + model download | `java/src/org/futo/inputmethod/latin/uix/settings/pages/RamblerSettings.kt`, `VoiceInput.kt`, `VoiceInputSettingKeys.kt`, `java/res/values/strings-rambler.xml` |
| Gradle wiring | `settings.gradle`, `build.gradle` (one line each) |

## Building

Same as FUTO Keyboard (clone **recursively** for the submodules), plus the NDK/CMake the build asks for.
The first build of `rambler-llm` downloads llama.cpp (pinned tag `b12020`; LFM3 needs b11960 or newer).
llama.cpp is built for `arm64-v8a` and `x86_64`; other ABIs get a stub and use the basic cleaner.

```
git clone --recursive <this repo>
./gradlew assembleUnstableDebug
./gradlew :rambler-llm:testDebugUnitTest
```

## License

FUTO Keyboard is licensed under the [FUTO Source First License 1.1](LICENSE.md), and this repository is
a derivative of it, so **those terms apply to this whole repository**. In short: you may use it for any
purpose, but you may modify and redistribute it **only for non-commercial purposes and free of charge**,
you must keep all notices and this license, and you must not remove the payment-related functionality.
Read [LICENSE.md](LICENSE.md) for the real terms. The original AOSP portions remain under Apache 2.0
(see [NOTICE](NOTICE)). The Rambler model, Liquid AI LFM3, has its own license (LFM 1.0).

FUTO asks that AI-generated pull requests not be sent to their repository; Rambler's additions were
written with an AI assistant, so don't upstream them as-is.

## Status

Not yet built or run on a device: see the commit history for what has been verified.

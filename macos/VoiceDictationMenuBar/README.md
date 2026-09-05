# Voice Dictation Menu Bar App

Fully hands-free voice dictation into Google Chrome — built for talking to
[Claude.ai](https://claude.ai)'s message box, but it targets whatever text
field is focused in Chrome. Say a wake phrase ("Hey Claude"), speak, and the
transcription is typed into the page for you. No hotkey required.

Everything runs **on-device**: wake-word detection (Picovoice Porcupine) and
speech-to-text (whisper.cpp, or Apple's on-device Speech framework as a
zero-setup fallback) never send audio anywhere.

## Architecture

| Piece | File | Responsibility |
|---|---|---|
| Wake word | `WakeWordEngine.swift` | Continuous on-device listening for the wake phrase and a separate stop phrase, via Picovoice Porcupine. |
| Dictation capture | `DictationRecorder.swift` | Records one utterance to a 16kHz mono WAV after the wake word fires; auto-stops on trailing silence or a max-duration cap. |
| Transcription | `Transcriber.swift` | Protocol with two implementations: `WhisperCppTranscriber` (shells out to a locally-built whisper.cpp binary) and `OnDeviceSpeechTranscriber` (Apple's `SFSpeechRecognizer` with `requiresOnDeviceRecognition = true`, used automatically until whisper.cpp is configured). |
| Text injection | `TextInjector.swift` | Confirms Chrome is frontmost with a focused, text-editable element via the Accessibility API, then types the text as synthetic keystrokes so it behaves exactly like manual typing (this is what makes it work reliably with Claude.ai's React-controlled composer). |
| Permissions | `PermissionsManager.swift` | Explains and requests Microphone + Accessibility access on first launch. |
| State/idle | `AppState.swift` | `asleep` / `listening` / `active` state machine, plus the auto-sleep idle timer. |
| Menu bar UI | `StatusItemController.swift`, `AppDelegate.swift` | `NSStatusItem` icon (changes per state) + Pause/Resume/Quit dropdown, and the orchestration wiring all of the above together. |
| Config | `Config.swift` | Loads/creates `~/Library/Application Support/VoiceDictationMenuBar/config.json`. |

This is a Swift Package (`Package.swift`), so **File > Open…** on this
folder in Xcode opens it as a full project you can build, run, and debug —
no hand-maintained `.xcodeproj` to go stale.

## Prerequisites

- macOS 13+, Xcode 15+.
- A free [Picovoice Console](https://console.picovoice.ai/) account, for:
  - Your personal **AccessKey**.
  - A trained "Hey Claude" wake word `.ppn` file (macOS platform).
  - A trained stop-phrase `.ppn` file, e.g. "Stop Listening".
- Optional, for the higher-accuracy transcriber: a local build of
  [whisper.cpp](https://github.com/ggerganov/whisper.cpp) and a GGML model
  (e.g. `ggml-base.en.bin`). Skip this and the app uses macOS's built-in
  on-device Speech framework instead — fully local either way, just lower
  accuracy on accents/background noise.

## Setup

### 1. Wake word models

See `Resources/WakeWords/README.md`. Drop your two `.ppn` files there.

### 2. Open and run

```
open Package.swift   # or File > Open… the VoiceDictationMenuBar folder in Xcode
```

Build & run the `VoiceDictationMenuBar` scheme. On first launch the app:

1. Explains why it needs microphone access, then triggers the system prompt.
2. Explains why it needs Accessibility access, then offers to open
   System Settings > Privacy & Security > Accessibility for you to enable it
   (there's no in-app prompt for this one — macOS requires the manual step).

Relaunch after granting Accessibility access (macOS doesn't hot-reload it
into a running process reliably).

A default `config.json` is created automatically at
`~/Library/Application Support/VoiceDictationMenuBar/config.json`:

```json
{
  "porcupineAccessKey": "",
  "wakeWordFile": "hey-claude_mac.ppn",
  "stopWordFile": "stop-listening_mac.ppn",
  "whisperCppBinaryPath": "",
  "whisperModelPath": "",
  "silenceTimeoutSeconds": 1.2,
  "maxUtteranceSeconds": 30,
  "autoSleepMinutes": 30,
  "wakeWordSensitivity": 0.6
}
```

Edit it (via the menu bar dropdown's **Open Config File…**, or any text
editor) and fill in:

- `porcupineAccessKey` — your Picovoice AccessKey. **Required** — the app
  shows an alert and stays asleep without one.
- `wakeWordFile` / `stopWordFile` — filenames of the `.ppn` files you placed
  in `Resources/WakeWords/`.
- `whisperCppBinaryPath` / `whisperModelPath` — absolute paths to your
  whisper.cpp CLI binary and a `.bin` model, if you built it. Leave blank to
  use the on-device Speech framework fallback (also requires granting
  Speech Recognition access when first prompted).
- `silenceTimeoutSeconds` — how long a pause ends a dictation capture.
- `maxUtteranceSeconds` — hard cap on a single dictation capture.
- `autoSleepMinutes` — inactivity period before auto-sleep (default 30).
- `wakeWordSensitivity` — 0.0 (fewer false wake-ups) to 1.0 (fewer misses).

Relaunch the app after editing the config.

### 3. Building a standalone `.app` (optional)

```
./Scripts/build_app_bundle.sh
```

Produces `dist/VoiceDictationMenuBar.app`, ad-hoc code-signed so TCC
(Microphone/Accessibility permission tracking) has a stable identity to key
grants off of. Move it to `/Applications` and launch it from there.

## Using it

1. Click into Claude.ai's message box in Chrome.
2. Say **"Hey Claude"**. The menu bar icon changes from the ear (listening)
   to a waveform (active) — it plays no sound, just watch the icon.
3. Speak your message. It ends automatically after you pause; the
   transcription is then typed into the message box.
4. Say **"Stop Listening"** at any time to put the app fully to sleep
   (moon icon) — for example before a meeting. Wake it back up either by
   saying the wake phrase again once the wake word engine is running, or
   via **Resume** in the menu bar dropdown.
5. The app also auto-sleeps on its own after `autoSleepMinutes` of no
   wake-word activity, to save battery/CPU. Resume the same way.

Menu bar dropdown: **Pause** (same as the stop phrase, but by click) /
**Resume** / **Open Config File…** / **Quit**.

## Permissions summary

| Permission | Why | Where granted |
|---|---|---|
| Microphone | Wake-word listening + dictation capture | System prompt on first launch |
| Accessibility | Find Chrome's focused text field and type into it | System Settings > Privacy & Security > Accessibility |
| Speech Recognition (only if not using whisper.cpp) | On-device transcription via `SFSpeechRecognizer` | System prompt on first use |

Voice control of page navigation, form submission, or any action beyond
typing into a focused text field is intentionally out of scope.

## Known limitations

- Not verified against a real build in this environment — this code was
  written and reviewed without access to Xcode/macOS SDKs (this project was
  authored on Linux), so it has not been compiled or run here. Build it in
  Xcode and validate against a live Chrome/Claude.ai session before relying
  on it, and check the Porcupine Swift SDK version you resolve
  (`Package.swift` pins `from: "3.0.0"`) against `PorcupineManager`'s exact
  initializer signature in `WakeWordEngine.swift` if you hit a compile error
  — Picovoice has made minor signature changes across major versions.
- Synthetic keystroke injection (`TextInjector.swift`) requires Chrome to be
  the frontmost app with a real text-editable element focused; it does not
  attempt to bring Chrome to the front or click into the composer for you.
- Only tested against Chrome's accessibility tree conventions
  (`com.google.Chrome` bundle id); other Chromium-based browsers would need
  their bundle id added to `TextInjector.chromeBundleIdentifier`.
- The stop phrase is only recognized while `listening` (i.e. between
  utterances) — Porcupine is intentionally stopped during an active
  recording to avoid contending with `DictationRecorder` for the microphone,
  so it can't interrupt a dictation already in progress.

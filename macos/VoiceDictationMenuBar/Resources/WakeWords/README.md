# Wake word models go here

Porcupine wake/stop phrases are trained per-user at
[console.picovoice.ai](https://console.picovoice.ai/) and downloaded as
`.ppn` files. They are **not** included in this repo (Picovoice's free tier
ties each model to your own AccessKey and account).

Steps:

1. Sign in at https://console.picovoice.ai/.
2. Under **Porcupine** > **Create Wake Word**, train a phrase like
   `Hey Claude` for **macOS**, and download the resulting `.ppn` file.
3. Repeat for a stop phrase, e.g. `Stop Listening`.
4. Drop both files in this folder, e.g.:
   - `hey-claude_mac.ppn`
   - `stop-listening_mac.ppn`
5. Make sure the filenames match `wakeWordFile` / `stopWordFile` in
   `~/Library/Application Support/VoiceDictationMenuBar/config.json` (see the
   top-level README).

Picovoice also ships a handful of free built-in keywords (e.g. "Porcupine",
"Computer", "Jarvis") if you just want to test the pipeline before training
a custom "Hey Claude" model — see `Porcupine.BuiltInKeyword` in the SDK.

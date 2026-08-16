# Voice Access Messenger

Hands-free, voice-controlled access to Outlook email and WhatsApp messages on
Android. Neither app exposes a public personal API this project is allowed
to use, so it works entirely by listening to notifications and driving each
app's own on-screen UI via Android's accessibility APIs — the same mechanism
screen readers use.

Three voice commands, working identically across both apps:

- **Read my messages** — reads every unread queued message aloud, oldest
  first, and offers to reply after each one.
- **Search `<keywords>`** — opens the target app's search UI, types the
  keywords, and reads back a summary of matches.
- **Reply** — after a message is read aloud, capture a spoken reply and send
  it in that conversation.

## Architecture

The app is four pieces, built in this order (see the commit history):

1. **`data/`** — Room database. A single `message_queue` table
   (`id, source_app, sender, preview_text, timestamp, read_aloud`) that
   `notification/MessageNotificationListenerService` inserts into and the
   rest of the app reads from.

2. **`notification/MessageNotificationListenerService`** — a
   `NotificationListenerService` filtered to Outlook and WhatsApp package
   names. On every notification it extracts sender/preview/timestamp and
   queues a row with `read_aloud = false`. It never treats notification text
   as final content — that text is frequently truncated by the OS or the
   app, so it's stored only as a fallback preview.

3. **`accessibility/`** — an `AccessibilityService` that does everything
   notifications can't:
   - `MessageAccessibilityService.openAndReadMessage` opens the specific
     email/chat matching a queued message's sender and scrapes the full
     visible body from the node tree.
   - `performSearch` opens the app's search UI, types keywords, and scrapes
     the resulting match list.
   - `sendReply` locates the reply/input field on the currently-open
     conversation, sets the text, and taps send.
   - `NodeTreeUtils` holds the generic (app-agnostic) tree-walking/scraping
     primitives; `AppUiConfig` holds the **hard-coded, per-app resource IDs**
     these primitives search for — see [Updating UI selectors](#updating-ui-selectors-after-an-app-update)
     below, this is the piece that breaks when Outlook or WhatsApp update
     their UI.

4. **`voice/` + `controller/` + `ui/`** — `TextToSpeechManager` and
   `SpeechToTextManager` are coroutine wrappers around Android's TTS and
   `SpeechRecognizer`. `VoiceAssistantController` is the single orchestrator:
   it queries the queue, drives the accessibility service, speaks results,
   and listens for a reply. `MainActivity` exposes a "Read My Messages"
   button and a tap-then-speak voice command button that parses phrases like
   "read my messages" / "search whatsapp for invoice".

## Setup

1. Open in Android Studio (Hedgehog+) or build with `./gradlew assembleDebug`.
   Requires network access to Google's Maven repo (`dl.google.com`) and
   Maven Central to resolve the Android Gradle Plugin and Room.
2. Install on a device with Outlook and/or WhatsApp installed.
3. Grant permissions on first run:
   - **Notification access** — required for the notification listener to see
     Outlook/WhatsApp notifications. The app's permission banner deep-links
     to `Settings > Apps > Special app access > Notification access`.
   - **Accessibility service** — required for reading full content, search,
     and reply. Deep-links to `Settings > Accessibility > Downloaded apps >
     Voice Access Messenger`.
   - **Microphone** — requested at runtime the first time you tap the voice
     command button; needed for both command capture and reply dictation.

## Updating UI selectors after an app update

Outlook and WhatsApp's view resource IDs are not a published API and will
drift across app updates. When a flow stops finding an element:

1. Open the target screen (inbox, a conversation, search, etc.) on a device
   connected to Android Studio.
2. **View > Tool Windows > Layout Inspector**, select the Outlook/WhatsApp
   process, and read the `resource-id` off the node in question.
3. Update the matching list in `accessibility/AppUiConfig.kt`. Every field is
   a list of *candidates* tried in order, and the framework falls back to
   text/content-description matching when none of them hit — so a same-name
   rename in one candidate slot is usually non-breaking, but a real layout
   change needs a new ID added here.

## Known limitations

- **No true always-listening wake word.** "Read my messages" is triggered by
  a button tap or by tapping the voice-command button and then speaking —
  there's no background hot-word detection (that needs a persistent
  foreground microphone service and typically a paid wake-word SDK like
  Porcupine, which is out of scope here).
- **Accessibility scraping is inherently best-effort.** It depends on
  hard-coded resource IDs that can change with any Outlook/WhatsApp update;
  the read flow falls back to the (possibly truncated) notification preview
  if a scrape fails so the app degrades rather than silently doing nothing.
- **Outlook's message body renders in a WebView.** Scraping relies on
  Chromium's accessibility tree being populated, which can lag slightly
  behind the window becoming visible — `AppUiSelectors.settleDelayMs` tunes
  this per app.
- **Single active flow at a time.** Read/search/reply all share one
  accessibility action lock, matching the fact that only one app can be in
  the foreground at once.
- **Not verified against a live build in this environment.** This code was
  written and reviewed without access to the Android SDK/Google's Maven
  repo (both are blocked by this environment's network policy), so it has
  not been compiled or run here — validate with `./gradlew assembleDebug`
  and on-device testing before relying on it.

## Permissions used

| Permission | Why |
|---|---|
| `BIND_NOTIFICATION_LISTENER_SERVICE` (system-granted after user opts in) | Read Outlook/WhatsApp notifications |
| `BIND_ACCESSIBILITY_SERVICE` (system-granted after user opts in) | Read full content, search, reply |
| `RECORD_AUDIO` | Voice command capture, reply dictation |
| `INTERNET` | `SpeechRecognizer` may use a network recognition backend |

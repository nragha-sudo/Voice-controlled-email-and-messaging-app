# Voice Access Messenger

Hands-free, voice-controlled access to Outlook email, WhatsApp messages, and
SMS texts on Android. Outlook and WhatsApp expose no public personal API this
project is allowed to use, so those two work entirely by listening to
notifications and driving each app's own on-screen UI via Android's
accessibility APIs — the same mechanism screen readers use. SMS is a third,
platform-level source: it's captured directly from the OS's SMS broadcast,
so it never needs accessibility scraping. All three sources land in the same
local database, tagged so they can always be queried separately.

The app also runs a small local HTTP API (see
[Remote API (Claude / Tailscale)](#remote-api-claude--tailscale) below) so an
external tool like Claude can list and mark-as-read messages per source over
your private Tailscale network.

Each source's logo on the main screen is a **play/pause** button, not a
one-shot "read" button: tap it to start reading that source's unread queue
oldest-first, tap it again to pause (nothing is lost — a paused message is
simply offered again, in the same order, next time). After every message,
four things can happen — **Replay** (say it again), **Done** (mark read and
remove it from the queue), **Reply** (capture and send a spoken reply,
which also marks it done), or **Skip** (leave it unread, try again later) —
and each one works by *either* saying the word aloud *or* tapping its icon
button, whichever is easier in the moment; a spoken word also barges in and
interrupts the message currently being read, rather than requiring you to
wait for it to finish. Search and Clear Cache are per-source (one button
each under Outlook/WhatsApp/SMS) plus a combined "clear everything" bar,
since search and cache-clearing are the same action whether it's scoped to
one source or all of them. SMS has no search (see
[Known limitations](#known-limitations)) since its full text is already
stored — there's nothing to search around a truncated preview for.

## Architecture

The app is six pieces, built in this order (see the commit history):

1. **`data/`** — Room database. A single `message_queue` table
   (`id, source_app, sender, preview_text, timestamp, read_aloud,
   read_on_source, phone_number, sms_thread_id, notification_key`) that
   `notification/MessageNotificationListenerService` and `sms/SmsReceiver`
   both insert into and the rest of the app reads from. Every row is tagged
   with its `source_app` (`OUTLOOK` / `WHATSAPP` / `SMS`), so every query —
   on-device or over the API — can filter to one source at a time instead of
   an undifferentiated combined stream. `phone_number`/`sms_thread_id` are
   SMS-only (null for Outlook/WhatsApp); `read_on_source` tracks whether a
   message has also been marked read on the *actual* SMS/WhatsApp app, not
   just this local queue (see `controller/MessageReadSync.kt`).

2. **`notification/MessageNotificationListenerService`** — a
   `NotificationListenerService` filtered to Outlook and WhatsApp package
   names. On every notification it extracts sender/preview/timestamp and
   queues a row with `read_aloud = false`. It never treats notification text
   as final content — that text is frequently truncated by the OS or the
   app, so it's stored only as a fallback preview.

2b. **`sms/SmsReceiver`** — a `BroadcastReceiver` for
   `Telephony.Sms.Intents.SMS_RECEIVED_ACTION`, the SMS equivalent of the
   notification listener above. Unlike Outlook/WhatsApp notifications, an
   SMS broadcast carries the message's full text already (no truncation to
   work around), so it's queued verbatim, tagged `source_app = SMS`, with
   the sender's phone number and platform thread id attached for later
   mark-as-read/deep-link use. Receiving SMS this way only needs
   RECEIVE_SMS/READ_SMS — it does **not** require this app to be the
   device's default SMS app (see Known limitations for what *does* need
   that role).

3. **`accessibility/`** — an `AccessibilityService` that does everything
   notifications can't (Outlook/WhatsApp only — SMS has no accessibility
   flow, see Known limitations):
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
   `SpeechRecognizer`; the latter's `listenOnce` takes an optional
   `onSpeechDetected` callback fired the instant speech is detected (well
   before the transcript is ready), which is what lets a spoken word "barge
   in" and cut off the TTS mid-message rather than waiting for it to finish.
   `VoiceAssistantController` is the single orchestrator: `togglePlayPause`
   is each source logo's tap handler (start, or pause-in-place if that
   source is already running); every message-decision point races two
   input sources for a `UserAction` (`REPLAY`/`DONE`/`REPLY`/`SKIP`) — a
   parsed spoken word, and a button tap via `submitAction` — via a shared
   `CompletableDeferred`, so voice and buttons are simply two ways of
   answering the same question, whichever gets there first. `MainActivity`
   wires each source's logo (icon-only, see Setup) to `togglePlayPause`,
   the Replay/Done/Reply/Skip icon row to `submitAction`, and each source's
   own Search/Clear Cache buttons to their per-source calls.

5. **`controller/MessageReadSync`** — the shared "mark read everywhere" step
   used by both the voice flow above (its "done"/successful-reply branches)
   and the API server below, so a message read via voice and a message read
   via Claude mark the real WhatsApp/SMS app identically instead of the API
   being a second, divergent code path. See its doc comment and Known
   limitations for exactly what "mark read on the source app" can and can't
   do for each source.

6. **`server/`** — `LocalApiServer` (a `fi.iki.elonen.NanoHTTPD` subclass)
   exposes the queue over HTTP with per-source filtering, kept alive by the
   `ApiServerService` foreground service and gated by `ApiKeyStore`'s shared
   secret. See [Remote API (Claude / Tailscale)](#remote-api-claude--tailscale).

### Debugging with logcat

Every non-UI piece logs under a per-class tag, so you can watch exactly which
selector/fallback path fires without attaching a debugger:

```
adb logcat -s VAM-NotificationListener VAM-Accessibility VAM-Controller VAM-SpeechToText VAM-Contacts
```

`VAM-Accessibility` is the most useful one when a read/search/reply flow
isn't finding the right screen element — it logs whether each lookup hit via
`AppUiConfig`'s resource IDs or fell back to text/content-description
matching (a `Log.w` fallback line is your cue that `AppUiConfig` needs a real
ID from Layout Inspector).

`VAM-SpeechToText` is the one to check when "reply/skip/done" doesn't seem to
be listening — it logs every stage of a listen attempt (`onReadyForSpeech`,
`onBeginningOfSpeech`, the decoded `onError` reason, the raw `onResults`
transcript) instead of silently swallowing failures, so you can tell apart:
mic permission missing (`ERROR_INSUFFICIENT_PERMISSIONS`), no recognizer
available on the device at all (logged before even trying), the mic opened
but heard nothing (`ERROR_SPEECH_TIMEOUT`), heard something but couldn't
transcribe it (`ERROR_NO_MATCH`), or it transcribed something that just
didn't contain "reply"/"skip"/"done" (visible in the `onResults` line, and
in `VAM-Controller`'s `heard instruction="..."` line).

### Speech recognition biasing toward contact names

Uncommon names (e.g. names ASR wasn't trained on) tend to get mis-transcribed
by the generic language model. If READ_CONTACTS is granted, every listen
attempt — the voice-command recognizer, the reply/skip/done prompt, and reply
dictation — passes the device's contact display names as recognizer
"biasing" hints (`RecognizerIntent.EXTRA_BIASING_STRINGS`, read by
`voice/ContactsProvider`). This is entirely optional: declining the
permission just means recognition falls back to the plain language model,
nothing else breaks. Support for this extra varies by recognizer
implementation/OS version — it's a hint, not a guarantee.

## Setup

1. Open in Android Studio (Hedgehog+) or build with `./gradlew assembleDebug`.
   Requires network access to Google's Maven repo (`dl.google.com`) and
   Maven Central to resolve the Android Gradle Plugin, Room, NanoHTTPD, and
   `androidx.security:security-crypto`.
2. Install on a device with Outlook and/or WhatsApp installed. SMS works on
   any device with a SIM/SMS capability, independent of the other two.
3. Grant permissions on first run:
   - **Notification access** — required for the notification listener to see
     Outlook/WhatsApp notifications. The app's permission banner deep-links
     to `Settings > Apps > Special app access > Notification access`.
   - **Accessibility service** — required for reading full content, search,
     and reply for Outlook/WhatsApp (not used for SMS). Deep-links to
     `Settings > Accessibility > Downloaded apps > Voice Access Messenger`.
   - **SMS access** (`RECEIVE_SMS` + `READ_SMS`, plus `SEND_SMS` for voice
     replies) — requested together automatically on first launch, or via the
     permission banner if `RECEIVE_SMS`/`READ_SMS` are declined. Declining
     `RECEIVE_SMS`/`READ_SMS` means SMS notifications are never queued;
     declining just `SEND_SMS` only disables the voice "reply" flow for SMS
     (reading/marking read still work). Outlook/WhatsApp are unaffected
     either way.
   - **Microphone** — requested at runtime the first time you tap any read
     button or the voice command button; needed for command capture,
     reply/skip/done listening, and reply dictation.
   - **Contacts** (optional) — requested alongside the microphone. Declining
     it is fine; it's only used to bias speech recognition toward contact
     names (see below) and nothing else depends on it.
   - **Notifications** (`POST_NOTIFICATIONS`, API 33+) — requested alongside
     the SMS permissions; needed for the local API server's persistent
     "still running" notification to actually display. The server itself
     still runs without it.

## Remote API (Claude / Tailscale)

The **Local API Server (for Claude)** section on the main screen starts an
embedded HTTP server (`server/LocalApiServer`, built on NanoHTTPD) that
exposes the same `message_queue` table over the network, with clean
per-source filtering so you can point Claude at just your WhatsApp queue,
just Outlook, or just SMS.

### Starting it

1. Tap **Start Server** on the main screen. It runs as a foreground service
   (persistent notification) so it keeps running with the screen off.
2. Tap **Copy API Key** to grab the shared secret every request must send as
   an `X-Api-Key` header (or `Authorization: Bearer <key>`). It's generated
   once on first use and stored in an Android Keystore-backed encrypted
   preferences file; **Regenerate** invalidates it and issues a new one (any
   client using the old key stops working until given the new one).

### Endpoints

All JSON. Port defaults to `8765` (`server/ApiServerService.PORT`). Every
route except `/health` requires the `X-Api-Key` header.

| Method | Path | Notes |
|---|---|---|
| GET | `/health` | No auth. Liveness check — use this first while setting up Tailscale. |
| GET | `/api/sources` | Each source (`OUTLOOK`/`WHATSAPP`/`SMS`) with its display name and unread count. |
| GET | `/api/messages?source=WHATSAPP&status=unread&limit=100` | `source` omitted = all three. `status` is `unread` (default) or `all`. `limit` caps `all` (1–500, default 100). |
| GET | `/api/messages/{id}` | One message's stored fields. |
| POST | `/api/messages/{id}/read` | Marks it read locally, and — SMS/WhatsApp only, see Known limitations — attempts to mark it read on the actual source app. Response includes `readOnSource` (bool) and a `note` explaining what actually happened. |
| POST | `/api/messages/{id}/open` | Deep-links into the real conversation: for WhatsApp/Outlook this reuses the same accessibility flow the on-device read-aloud button uses, so it lands in the same chat/email a manual read-aloud would, and returns the freshly-scraped body if available; for SMS it opens the platform Messages app to that thread. |

Example, once you have the Tailscale IP and API key (see below):

```
curl http://<tailscale-ip>:8765/api/messages?source=WHATSAPP\&status=unread \
  -H "X-Api-Key: <key>"

curl -X POST http://<tailscale-ip>:8765/api/messages/42/read \
  -H "X-Api-Key: <key>"
```

### Reaching it over Tailscale

The server is meant to be reached over your private Tailscale network, not
the open internet — it isn't hardened against arbitrary internet traffic,
and the API key is the only thing between the network and your messages.

1. Install Tailscale on the Android device: Play Store (or F-Droid/APK) →
   search "Tailscale" → install → sign in with the same Tailscale account/
   network you'll use on the client (laptop, etc.) → confirm the device
   shows up in `tailscale status` / the Tailscale admin console.
2. Install/sign in to Tailscale on whatever machine Claude runs from, on the
   same tailnet.
3. Find the phone's Tailscale IP (Tailscale app → device details, or
   `tailscale status` from another device on the tailnet — it's the
   `100.x.y.z` address).
4. Confirm reachability before worrying about the API key:
   `curl http://<tailscale-ip>:8765/health` should return
   `{"status":"ok"}`. If it doesn't: confirm both devices show "Connected"
   in Tailscale, that the app's server is actually started (see the
   "Server running on port 8765" status text), and that nothing else on the
   phone is blocking port 8765.
5. Once `/health` works, add the `X-Api-Key` header (copied from the app) to
   every other request — **the key is still required on Tailscale**; being
   on the private network only replaces open-internet exposure, it does not
   replace authentication.
6. Give Claude the base URL (`http://<tailscale-ip>:8765`) and the API key
   so it can call the endpoints above.

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
  Not applicable to SMS, which stores its full text directly.
- **Outlook's message body renders in a WebView.** Scraping relies on
  Chromium's accessibility tree being populated, which can lag slightly
  behind the window becoming visible — `AppUiSelectors.settleDelayMs` tunes
  this per app.
- **Single active flow at a time.** Read/search/reply all share one
  accessibility action lock, matching the fact that only one app can be in
  the foreground at once.
- **"Pause" restarts from a snapshot, it doesn't suspend mid-message.**
  Tapping a source's logo again while it's reading cancels the in-flight
  coroutine and halts speech immediately; because a paused-mid-message row
  is left unread (never marked done), tapping the same logo again simply
  re-fetches the unread queue and starts over from the earliest message —
  which, in practice, is the very message that was paused. Indistinguishable
  from true pause/resume from the user's side, but worth knowing it isn't
  literally suspending and resuming the same coroutine state.
- **Voice barge-in can hear itself.** Listening runs concurrently with the
  TTS speaking a message (so a spoken word can interrupt it), which means
  the mic is open while the phone's own speaker is talking. Most modern
  devices apply acoustic echo cancellation to `VOICE_RECOGNITION`-source
  audio automatically, but it isn't guaranteed on every device — on one
  without it, the recognizer may occasionally pick up the TTS's own voice.
  The on-screen Replay/Done/Reply/Skip buttons are unaffected by this either
  way, since they don't involve the mic at all.
- **SMS has no search flow.** Search drives each app's on-screen search UI
  (there's nothing to search around for SMS, since its text is already
  stored in full) — a spoken "search sms for …" is declined with an
  explanation rather than attempted.
- **SMS reply sends directly via `SmsManager`, not through accessibility.**
  Unlike Outlook/WhatsApp (which type into the app's own on-screen reply
  field), SMS has no on-screen UI to drive for this, so a spoken "reply" to
  an SMS is sent with `sms/SmsSender.kt`'s `SmsManager.sendMultipartTextMessage`
  instead. This is a normal dangerous-permission (`SEND_SMS`) action — unlike
  marking a message read (below), sending does *not* require default-SMS-app
  status, so it works on a normal install once the permission is granted. If
  `SEND_SMS` was declined or the message has no stored phone number, the
  reply is reported as not sent (falls back to "skipped for later") rather
  than silently failing.
- **Marking a message read on the real SMS app requires this app to be the
  device's default SMS handler, which it deliberately is not.** Android only
  honors writes to the platform SMS provider (including flipping the `read`
  flag) from whichever app currently holds the default-SMS-app role
  (`Settings > Apps > Default apps > SMS app`); every other app's writes,
  including this one's, are rejected outright, even with READ_SMS/
  RECEIVE_SMS granted. Becoming the default SMS app means implementing the
  full default-app contract (composing, receiving via `SMS_DELIVER` instead
  of `SMS_RECEIVED`, a quick-response UI, etc.) — a much larger surface than
  this app's read-only/voice-control purpose, so it's not attempted. In
  practice: `POST /api/messages/{id}/read` and the voice "done" flow always
  succeed at marking the local queue read; for SMS, `readOnSource` in the
  response will be `false` with a `note` explaining this, and the message
  will still show unread in the device's actual Messages app. This is a
  real, reported Android platform limitation, not a bug or an oversight.
- **Marking a WhatsApp chat read on WhatsApp itself has no public API
  either**, so this app's only lever is the same one it already uses to
  read a message aloud: opening the real conversation via the accessibility
  service, which causes WhatsApp to flip its own unread state (and send a
  read receipt, if enabled) because the chat was genuinely displayed. This
  works, but depends on the accessibility service being enabled and the app
  being foregroundable at that moment — if either fails, `readOnSource` is
  reported `false` with a `note`, never silently assumed to have worked. A
  message that was just read aloud through the voice flow is already marked
  this way for free (the chat was opened moments earlier to read it); one
  read purely through the API without ever calling `/open` first triggers
  the same open-and-scrape step to accomplish it.
- **Outlook has no mark-as-read-on-source path at all** — no public API, and
  no safe on-screen affordance the accessibility service could drive without
  risking an accidental action on the wrong email — so it's skipped by
  design, both for voice "done" and the API; only the local queue updates.
- **The local API server is not hardened for open-internet exposure.** It's
  designed to be reached only over Tailscale (see above); the API key
  guards it either way, but don't port-forward it to the public internet.
- **Not verified against a live build in this environment.** This code was
  written and reviewed without access to the Android SDK/Google's Maven
  repo (both are blocked by this environment's network policy), so it has
  not been compiled or run here — validate with `./gradlew assembleDebug`
  and on-device testing (including the Room 1→2 migration, on a device with
  existing queued messages) before relying on it.

## Permissions used

| Permission | Why |
|---|---|
| `BIND_NOTIFICATION_LISTENER_SERVICE` (system-granted after user opts in) | Read Outlook/WhatsApp notifications |
| `BIND_ACCESSIBILITY_SERVICE` (system-granted after user opts in) | Read full content, search, reply for Outlook/WhatsApp; opening a WhatsApp chat also marks it read there |
| `RECEIVE_SMS`, `READ_SMS` | Capture incoming SMS into the queue, tagged `source_app = SMS`. Does **not** grant or require default-SMS-app status. |
| `SEND_SMS` | Sends a spoken "reply" to an SMS via `SmsManager`. Also does **not** require default-SMS-app status. |
| `RECORD_AUDIO` | Voice command capture, reply dictation |
| `INTERNET` | `SpeechRecognizer` may use a network recognition backend; the local API server also listens on this permission's socket access |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Keeps the local API server running while the app is backgrounded |
| `POST_NOTIFICATIONS` (API 33+) | Shows the API server's persistent "running" notification |
| `READ_CONTACTS` (optional) | Biases speech recognition toward contact names |

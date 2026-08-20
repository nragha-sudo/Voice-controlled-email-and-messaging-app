package com.voiceaccess.messenger.controller

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.voiceaccess.messenger.R
import com.voiceaccess.messenger.accessibility.MessageAccessibilityService
import com.voiceaccess.messenger.data.MessageEntity
import com.voiceaccess.messenger.data.MessageRepository
import com.voiceaccess.messenger.data.SourceApp
import com.voiceaccess.messenger.sms.SmsSender
import com.voiceaccess.messenger.voice.ContactsProvider
import com.voiceaccess.messenger.voice.SpeechToTextManager
import com.voiceaccess.messenger.voice.TextToSpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Stage 4 of the pipeline: the single entry point tying the message queue,
 * the accessibility service, and text-to-speech/speech-to-text together
 * into the three voice commands the app supports — read unread messages,
 * search, and reply.
 */
class VoiceAssistantController(
    private val context: Context,
    private val repository: MessageRepository,
) {
    private val tts = TextToSpeechManager(context)
    private val stt = SpeechToTextManager(context)
    private val readSync = MessageReadSync(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeJob: Job? = null

    private val _status = MutableStateFlow(context.getString(R.string.status_idle))
    val status: StateFlow<String> = _status

    /** True while a read/search flow is actively running — drives the Stop Reading button's visibility. */
    private val _isReading = MutableStateFlow(false)
    val isReading: StateFlow<Boolean> = _isReading

    /**
     * "Read my messages" (or "read outlook/whatsapp messages"): queries
     * unread rows — filtered to [app] if given, otherwise both apps — reads
     * each aloud, and listens for reply/skip/done after every one.
     */
    fun readUnreadMessages(app: SourceApp? = null) {
        launchExclusive("readUnreadMessages(${app?.name})") { runReadUnreadMessages(app) }
    }

    /** "Search [keywords]", optionally scoped to one app; null searches both. */
    fun search(app: SourceApp?, keywords: String) {
        if (keywords.isBlank()) return
        launchExclusive("search") { runSearch(app, keywords) }
    }

    /**
     * Stops an in-progress read/search immediately — halts whatever's
     * currently being spoken (rather than waiting for the current utterance
     * to finish) and cancels the rest of the run. Any message that wasn't
     * explicitly marked done stays unread, so it's read again next time —
     * same non-destructive default as a timeout or "skip".
     */
    fun stopReading() {
        if (activeJob?.isActive != true) return
        activeJob?.cancel()
        tts.stop()
        _status.value = context.getString(R.string.status_ready)
    }

    private fun launchExclusive(label: String, block: suspend () -> Unit) {
        if (activeJob?.isActive == true) {
            Log.d(TAG, "$label ignored — a flow is already running")
            return
        }
        activeJob = scope.launch {
            _isReading.value = true
            try {
                block()
            } finally {
                _isReading.value = false
            }
        }
    }

    /** Best-effort natural-language dispatch for free-form voice/typed commands. */
    fun handleVoiceCommand(rawText: String) {
        val text = rawText.trim()
        val lower = text.lowercase()
        when {
            lower.contains("read") && lower.contains("messag") -> {
                val app = appMentionedIn(lower)
                readUnreadMessages(app)
            }
            lower.startsWith("search") -> {
                val (app, keywords) = parseSearchCommand(lower)
                search(app, keywords)
            }
            else -> _status.value = "Didn't understand: \"$text\""
        }
    }

    fun shutdown() {
        activeJob?.cancel()
        tts.shutdown()
    }

    private fun appMentionedIn(lowerText: String): SourceApp? = when {
        lowerText.contains("whatsapp") -> SourceApp.WHATSAPP
        lowerText.contains("outlook") -> SourceApp.OUTLOOK
        lowerText.contains("sms") || lowerText.contains("text message") -> SourceApp.SMS
        else -> null
    }

    private fun parseSearchCommand(lowerText: String): Pair<SourceApp?, String> {
        var remainder = lowerText.removePrefix("search").trim()
        val app = when {
            remainder.startsWith("whatsapp") -> SourceApp.WHATSAPP.also { remainder = remainder.removePrefix("whatsapp").trim() }
            remainder.startsWith("outlook") -> SourceApp.OUTLOOK.also { remainder = remainder.removePrefix("outlook").trim() }
            else -> null
        }
        remainder = remainder.removePrefix("in").trim().removePrefix("for").trim()
        return app to remainder
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** Contact names to bias speech recognition toward (e.g. so "Sruthikutti" transcribes correctly). */
    private fun loadBiasingNames(): List<String> =
        if (hasContactsPermission()) ContactsProvider.loadDisplayNames(context) else emptyList()

    private suspend fun runReadUnreadMessages(app: SourceApp?) {
        val unread = repository.getUnread(app)
        Log.d(TAG, "runReadUnreadMessages(${app?.name}): ${unread.size} unread message(s)")
        if (unread.isEmpty()) {
            tts.speak(context.getString(R.string.no_unread_messages))
            _status.value = context.getString(R.string.status_ready)
            return
        }

        val accessibility = MessageAccessibilityService.currentInstance()
        // Listening for reply/skip/done needs the mic permission granted *before*
        // this loop starts — MainActivity requests it up front on both read
        // buttons, but if the user somehow got here without it, SpeechRecognizer
        // fails instantly rather than actually listening, which looked like "the
        // app doesn't pause at all." Detect that case explicitly instead of
        // silently racing through every message.
        if (accessibility == null || !hasRecordAudioPermission()) {
            Log.w(TAG, "runReadUnreadMessages: cannot listen for reply/skip/done — " +
                "accessibilityServiceEnabled=${accessibility != null} micPermissionGranted=${hasRecordAudioPermission()}. " +
                "Every message this run will be left unread (treated as skipped).")
        }
        // Loaded once per run rather than per message — a full contacts
        // query for every listenOnce() call would be wasteful.
        val biasingNames = loadBiasingNames()

        for (message in unread) {
            _status.value = "Reading ${message.sourceApp.displayName} message from ${message.sender}…"

            // SMS has no accessibility flow to scrape (see AppUiConfig.forApp) —
            // it arrives with its full text already via broadcast, so unlike
            // Outlook/WhatsApp there's no truncated preview to work around.
            val body = if (message.sourceApp == SourceApp.SMS) {
                message.previewText
            } else {
                accessibility?.openAndReadMessage(message)?.takeIf { it.isNotBlank() }
                    ?: message.previewText.also {
                        Log.w(TAG, "runReadUnreadMessages: id=${message.id} using notification preview as fallback " +
                            "(accessibility service ${if (accessibility == null) "not connected" else "returned nothing"})")
                    }
            }

            tts.speak("New ${message.sourceApp.displayName} message from ${message.sender}. $body")

            // Guard on the variable itself (not a derived boolean) so the
            // compiler can smart-cast `accessibility` to non-null below —
            // handleSpokenReply/sendReply both require a non-null instance.
            if (accessibility == null || !hasRecordAudioPermission()) {
                // Can't ask, so we can't confirm "done" — leave it unread
                // rather than guessing, matching the skip semantics from Issue 3.
                continue
            }

            tts.speak(context.getString(R.string.prompt_reply_or_next))
            val instruction = stt.listenOnce(timeoutMs = LISTEN_WINDOW_MS, biasingStrings = biasingNames)?.lowercase()
            Log.d(TAG, "runReadUnreadMessages: id=${message.id} heard instruction=\"$instruction\"")

            when {
                instruction == null -> {
                    // Timeout with no input: per spec this must default to skip
                    // (stays unread), never to done.
                    tts.speak(context.getString(R.string.skipped_for_later))
                }
                instruction.contains("stop") || instruction.contains("cancel") -> {
                    tts.speak(context.getString(R.string.stopping_read_aloud))
                    _status.value = context.getString(R.string.status_ready)
                    return
                }
                instruction.contains("reply") -> {
                    val sent = handleSpokenReply(accessibility, message, biasingNames)
                    if (sent) {
                        // For WhatsApp specifically, a successful reply means the real
                        // conversation is still open on screen right now, same as the plain
                        // "done" case below — this flag is ignored for SMS/Outlook.
                        readSync.markRead(repository, message, whatsAppAlreadyOpened = true)
                        tts.speak(context.getString(R.string.marked_as_done))
                    } else {
                        tts.speak(context.getString(R.string.skipped_for_later))
                    }
                }
                instruction.contains("done") -> {
                    // openAndReadMessage() a few lines up already brought WhatsApp's real
                    // conversation to the foreground to read it aloud — that's what marks
                    // it read on WhatsApp's side, so tell readSync not to reopen it.
                    readSync.markRead(repository, message, whatsAppAlreadyOpened = true)
                    tts.speak(context.getString(R.string.marked_as_done))
                }
                instruction.contains("skip") -> {
                    tts.speak(context.getString(R.string.skipped_for_later))
                }
                else -> {
                    // Unrecognized speech: same as timeout, default to the
                    // non-destructive choice rather than guessing "done".
                    tts.speak(context.getString(R.string.skipped_for_later))
                }
            }
        }
        _status.value = context.getString(R.string.status_ready)
    }

    /**
     * Returns true only if a reply was actually captured and sent — callers
     * decide skip vs done from that. SMS has no accessibility-driven reply
     * flow (see AppUiConfig.forApp) since there's no on-screen UI to drive
     * for it; instead it sends directly via [SmsSender] (SEND_SMS is a
     * normal dangerous permission, unlike the mark-as-read write in
     * [com.voiceaccess.messenger.sms.SmsReadMarker], so this doesn't need
     * default-SMS-app status).
     */
    private suspend fun handleSpokenReply(
        accessibility: MessageAccessibilityService,
        message: MessageEntity,
        biasingNames: List<String>,
    ): Boolean {
        val app = message.sourceApp

        tts.speak(context.getString(R.string.prompt_say_reply))
        val replyText = stt.listenOnce(timeoutMs = REPLY_CAPTURE_TIMEOUT_MS, biasingStrings = biasingNames)
        if (replyText.isNullOrBlank()) {
            Log.d(TAG, "handleSpokenReply: no reply captured within timeout")
            return false
        }

        if (app == SourceApp.SMS) {
            val phoneNumber = message.phoneNumber
            if (phoneNumber.isNullOrBlank() || !hasSendSmsPermission()) {
                Log.w(TAG, "handleSpokenReply: id=${message.id} cannot send SMS reply — " +
                    "phoneNumber=${phoneNumber != null} sendSmsPermissionGranted=${hasSendSmsPermission()}")
                return false
            }
            val sent = SmsSender.send(context, phoneNumber, replyText)
            Log.d(TAG, "handleSpokenReply: id=${message.id} SmsSender result=$sent")
            return sent
        }

        val sent = accessibility.sendReply(app, replyText)
        Log.d(TAG, "handleSpokenReply: app=${app.name} sendReply result=$sent")
        return sent
    }

    private fun hasSendSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private suspend fun runSearch(app: SourceApp?, keywords: String) {
        // Search drives each app's own on-screen search UI (see AppUiConfig),
        // which only exists for Outlook/WhatsApp — SMS stores its full text
        // already (no truncation to search around a scrape of), so there's no
        // SMS search UI to drive here.
        if (app == SourceApp.SMS) {
            tts.speak("SMS search isn't supported — SMS messages already store their full text, so there's nothing to search on-screen.")
            _status.value = context.getString(R.string.status_ready)
            return
        }

        val accessibility = MessageAccessibilityService.currentInstance()
        if (accessibility == null) {
            tts.speak(context.getString(R.string.status_accessibility_missing))
            _status.value = context.getString(R.string.status_accessibility_missing)
            return
        }

        val targets = app?.let { listOf(it) } ?: listOf(SourceApp.OUTLOOK, SourceApp.WHATSAPP)
        for (target in targets) {
            _status.value = "Searching ${target.displayName} for \"$keywords\"…"
            val results = accessibility.performSearch(target, keywords)
            val summary = if (results.isEmpty()) {
                "No matches found in ${target.displayName}."
            } else {
                "Found ${results.size} matches in ${target.displayName}. " + results.take(5).joinToString(". ")
            }
            tts.speak(summary)
        }
        _status.value = context.getString(R.string.status_ready)
    }

    private companion object {
        private const val TAG = "VAM-Controller"
        private const val LISTEN_WINDOW_MS = 6_000L
        private const val REPLY_CAPTURE_TIMEOUT_MS = 12_000L
    }
}

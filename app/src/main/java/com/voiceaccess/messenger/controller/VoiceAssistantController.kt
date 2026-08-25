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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What a message-decision point resolves to — from *either* a recognized spoken word or a button tap, whichever arrives first. See [VoiceAssistantController.submitAction]. */
enum class UserAction {
    REPLAY, DONE, REPLY, SKIP,

    /** Voice-only (no dedicated button, matching the app's play/pause-per-source UI): fully ends the current read-through, same as the old Stop Reading button used to. */
    CANCEL,
}

/**
 * Stage 4 of the pipeline: the single entry point tying the message queue,
 * the accessibility service, and text-to-speech/speech-to-text together
 * into the app's per-source read/reply flow.
 *
 * Each source's on-screen logo is a play/pause toggle
 * ([togglePlayPause]) rather than a one-shot "read" button: tapping it
 * starts a read-through of that source's unread queue; tapping the *same*
 * logo again pauses it (halts speech/listening immediately) rather than
 * cancelling — nothing is lost, since a paused-mid-message read is left
 * unread and simply comes up again, in the same order, the next time that
 * logo is tapped. That's a deliberate simplification over true low-level
 * coroutine suspension: it's restart-from-the-snapshot, not byte-exact
 * resume, but it's indistinguishable from the user's side and far less
 * failure-prone to get right.
 *
 * Every "what do you want to do with this message" decision (replay/done/
 * reply/skip) can be answered by *either* a spoken word or the matching
 * on-screen button — see [submitAction] and [presentMessageAndAwaitAction]
 * — whichever arrives first wins, and a spoken word interrupts (barges in
 * on) the message currently being read aloud the instant speech is
 * detected, not just after it finishes.
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
    private var activeApp: SourceApp? = null

    /** Set only while a message is actively being presented (spoken + awaiting a decision); completed by whichever of voice or a button gets there first. See [submitAction]. */
    private var pendingAction: CompletableDeferred<UserAction?>? = null

    private val _status = MutableStateFlow(context.getString(R.string.status_idle))
    val status: StateFlow<String> = _status

    /** True while a read/search flow is actively running. */
    private val _isReading = MutableStateFlow(false)
    val isReading: StateFlow<Boolean> = _isReading

    /**
     * The play/pause tap handler for a source's logo button. Tapping the
     * currently-playing source pauses it; tapping it (or any source) while
     * something else is active switches to it, cancelling the other one.
     */
    fun togglePlayPause(app: SourceApp) {
        if (activeApp == app && activeJob?.isActive == true) {
            Log.d(TAG, "togglePlayPause(${app.name}): pausing")
            cancelActive()
            _status.value = context.getString(R.string.status_ready)
            return
        }

        cancelActive()
        activeApp = app
        activeJob = scope.launch {
            _isReading.value = true
            try {
                runReadUnreadMessages(app)
            } finally {
                _isReading.value = false
                if (activeApp == app) activeApp = null
            }
        }
    }

    /** The combined "read my messages" voice phrase (no source named) — same mechanics as [togglePlayPause], just not tied to any one logo. */
    private fun startCombinedRead() {
        cancelActive()
        activeJob = scope.launch {
            _isReading.value = true
            try {
                runReadUnreadMessages(null)
            } finally {
                _isReading.value = false
            }
        }
    }

    /**
     * Answers whatever decision point is currently open — the on-screen
     * Replay/Done/Reply/Skip buttons all call this. A no-op (with a status
     * message, not silently ignored) if nothing is actually awaiting a
     * decision right now, e.g. no read-through is active.
     */
    fun submitAction(action: UserAction) {
        val deferred = pendingAction
        if (deferred == null || deferred.isCompleted) {
            Log.d(TAG, "submitAction($action): nothing is awaiting a decision right now")
            _status.value = context.getString(R.string.status_nothing_pending)
            return
        }
        deferred.complete(action)
    }

    /** "Search [keywords]", optionally scoped to one source; null searches Outlook+WhatsApp (SMS has no search UI to drive — see [runSearch]). */
    fun search(app: SourceApp?, keywords: String) {
        if (keywords.isBlank()) return
        launchExclusive("search") { runSearch(app, keywords) }
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

    /** Cancels whatever's currently active (a read-through or a search) and halts speech/listening immediately. */
    private fun cancelActive() {
        val job = activeJob
        activeJob = null
        activeApp = null
        job?.cancel()
        tts.stop()
        pendingAction?.let { if (!it.isCompleted) it.complete(null) }
        pendingAction = null
    }

    /** Best-effort natural-language dispatch for free-form voice/typed commands (the Voice Command button). */
    fun handleVoiceCommand(rawText: String) {
        val text = rawText.trim()
        val lower = text.lowercase()
        when {
            lower.contains("read") && lower.contains("messag") -> {
                val app = appMentionedIn(lower)
                if (app != null) togglePlayPause(app) else startCombinedRead()
            }
            lower.startsWith("search") -> {
                val (app, keywords) = parseSearchCommand(lower)
                search(app, keywords)
            }
            else -> _status.value = "Didn't understand: \"$text\""
        }
    }

    fun shutdown() {
        cancelActive()
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

    private fun hasSendSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
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
        if (!hasRecordAudioPermission()) {
            Log.w(TAG, "runReadUnreadMessages: RECORD_AUDIO not granted — voice replay/done/reply/skip won't " +
                "be heard this run, but the on-screen buttons still work for every message.")
        }
        // Loaded once per run rather than per message.
        val biasingNames = loadBiasingNames()

        for (message in unread) {
            var replay = true
            while (replay) {
                replay = false
                val presentation = presentMessageAndAwaitAction(message, biasingNames)
                when (presentation.action) {
                    UserAction.CANCEL -> {
                        tts.speak(context.getString(R.string.stopping_read_aloud))
                        _status.value = context.getString(R.string.status_ready)
                        return
                    }
                    UserAction.REPLAY -> replay = true
                    UserAction.DONE -> {
                        readSync.markReadAndDelete(repository, message, whatsAppAlreadyOpened = presentation.whatsAppOpened)
                        tts.speak(context.getString(R.string.marked_as_done))
                    }
                    UserAction.REPLY -> {
                        val sent = handleReplyCapture(message, biasingNames)
                        if (sent) {
                            readSync.markReadAndDelete(repository, message, whatsAppAlreadyOpened = true)
                            tts.speak(context.getString(R.string.marked_as_done))
                        } else {
                            tts.speak(context.getString(R.string.skipped_for_later))
                        }
                    }
                    UserAction.SKIP, null -> {
                        // Timeout, unrecognized speech, or an explicit "skip" all land here —
                        // the same non-destructive default: leave it unread, try again later.
                        tts.speak(context.getString(R.string.skipped_for_later))
                    }
                }
            }
        }
        _status.value = context.getString(R.string.status_ready)
    }

    private data class PresentationResult(val action: UserAction?, val whatsAppOpened: Boolean)

    /**
     * Speaks [message]'s body (scraped live for Outlook/WhatsApp via the
     * accessibility service, used as-is for SMS — see AppUiConfig.forApp)
     * plus the reply/skip/done/replay prompt, then races two input sources
     * for the user's decision: a recognized spoken word, and a button tap
     * via [submitAction] — whichever completes [pendingAction] first wins.
     * The recognizer runs *concurrently* with the speech (not after it), so
     * a spoken word barges in and immediately cuts the TTS off, rather than
     * requiring the user to wait for the whole message to finish first.
     */
    private suspend fun presentMessageAndAwaitAction(
        message: MessageEntity,
        biasingNames: List<String>,
    ): PresentationResult {
        _status.value = "Reading ${message.sourceApp.displayName} message from ${message.sender}…"

        var whatsAppOpened = false
        val body = if (message.sourceApp == SourceApp.SMS) {
            message.previewText
        } else {
            val scraped = MessageAccessibilityService.currentInstance()
                ?.openAndReadMessage(message)
                ?.takeIf { it.isNotBlank() }
            if (scraped != null && message.sourceApp == SourceApp.WHATSAPP) whatsAppOpened = true
            scraped ?: message.previewText.also {
                Log.w(TAG, "presentMessageAndAwaitAction: id=${message.id} using notification preview as fallback")
            }
        }

        val spoken = "New ${message.sourceApp.displayName} message from ${message.sender}. $body. " +
            context.getString(R.string.prompt_reply_or_next)

        val deferred = CompletableDeferred<UserAction?>()
        pendingAction = deferred

        val speakJob = scope.launch { tts.speak(spoken) }
        val listenJob = scope.launch {
            val heard = stt.listenOnce(
                timeoutMs = PRESENTATION_LISTEN_WINDOW_MS,
                biasingStrings = biasingNames,
                onSpeechDetected = { tts.stop() },
            )
            val parsed = parseUserAction(heard)
            Log.d(TAG, "presentMessageAndAwaitAction: id=${message.id} heard=\"$heard\" parsed=$parsed")
            if (!deferred.isCompleted) deferred.complete(parsed)
        }

        val action = deferred.await()
        listenJob.cancel()
        speakJob.cancel()
        tts.stop()
        pendingAction = null

        return PresentationResult(action, whatsAppOpened)
    }

    private fun parseUserAction(heard: String?): UserAction? {
        val lower = heard?.lowercase() ?: return null
        return when {
            lower.contains("stop") || lower.contains("cancel") -> UserAction.CANCEL
            lower.contains("replay") || lower.contains("repeat") -> UserAction.REPLAY
            lower.contains("reply") -> UserAction.REPLY
            lower.contains("done") -> UserAction.DONE
            lower.contains("skip") -> UserAction.SKIP
            else -> null
        }
    }

    /**
     * Captures a spoken reply and sends it. WhatsApp/Outlook go through the
     * accessibility service's on-screen reply flow; SMS has no such UI to
     * drive, so it sends directly via [SmsSender] (SEND_SMS doesn't need
     * default-SMS-app status, unlike marking read).
     */
    private suspend fun handleReplyCapture(message: MessageEntity, biasingNames: List<String>): Boolean {
        tts.speak(context.getString(R.string.prompt_say_reply))
        val replyText = stt.listenOnce(timeoutMs = REPLY_CAPTURE_TIMEOUT_MS, biasingStrings = biasingNames)
        if (replyText.isNullOrBlank()) {
            Log.d(TAG, "handleReplyCapture: id=${message.id} no reply captured within timeout")
            return false
        }

        if (message.sourceApp == SourceApp.SMS) {
            val phoneNumber = message.phoneNumber
            if (phoneNumber.isNullOrBlank() || !hasSendSmsPermission()) {
                Log.w(TAG, "handleReplyCapture: id=${message.id} cannot send SMS reply — " +
                    "phoneNumber=${phoneNumber != null} sendSmsPermissionGranted=${hasSendSmsPermission()}")
                return false
            }
            return SmsSender.send(context, phoneNumber, replyText)
        }

        val accessibility = MessageAccessibilityService.currentInstance() ?: run {
            Log.e(TAG, "handleReplyCapture: id=${message.id} accessibility service not connected")
            return false
        }
        return accessibility.sendReply(message.sourceApp, replyText)
    }

    private suspend fun runSearch(app: SourceApp?, keywords: String) {
        // Search drives each app's own on-screen search UI (see AppUiConfig),
        // which only exists for Outlook/WhatsApp — SMS stores its full text
        // already (no truncation to search around a scrape of), so there's no
        // SMS search UI to drive here.
        if (app == SourceApp.SMS) {
            tts.speak(context.getString(R.string.sms_search_unsupported))
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
        private const val PRESENTATION_LISTEN_WINDOW_MS = 20_000L
        private const val REPLY_CAPTURE_TIMEOUT_MS = 12_000L
    }
}

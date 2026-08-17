package com.voiceaccess.messenger.controller

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.voiceaccess.messenger.R
import com.voiceaccess.messenger.accessibility.MessageAccessibilityService
import com.voiceaccess.messenger.data.MessageRepository
import com.voiceaccess.messenger.data.SourceApp
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeJob: Job? = null

    private val _status = MutableStateFlow(context.getString(R.string.status_idle))
    val status: StateFlow<String> = _status

    /**
     * "Read my messages" (or "read outlook/whatsapp messages"): queries
     * unread rows — filtered to [app] if given, otherwise both apps — reads
     * each aloud, and listens for reply/skip/done after every one.
     */
    fun readUnreadMessages(app: SourceApp? = null) {
        if (activeJob?.isActive == true) {
            Log.d(TAG, "readUnreadMessages(${app?.name}) ignored — a flow is already running")
            return
        }
        activeJob = scope.launch { runReadUnreadMessages(app) }
    }

    /** "Search [keywords]", optionally scoped to one app; null searches both. */
    fun search(app: SourceApp?, keywords: String) {
        if (keywords.isBlank() || activeJob?.isActive == true) return
        activeJob = scope.launch { runSearch(app, keywords) }
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

        for (message in unread) {
            _status.value = "Reading ${message.sourceApp.displayName} message from ${message.sender}…"

            val body = accessibility?.openAndReadMessage(message)?.takeIf { it.isNotBlank() }
                ?: message.previewText.also {
                    Log.w(TAG, "runReadUnreadMessages: id=${message.id} using notification preview as fallback " +
                        "(accessibility service ${if (accessibility == null) "not connected" else "returned nothing"})")
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
            val instruction = stt.listenOnce(timeoutMs = LISTEN_WINDOW_MS)?.lowercase()
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
                    val sent = handleSpokenReply(accessibility, message.sourceApp)
                    if (sent) {
                        repository.markReadAloud(message.id)
                        tts.speak(context.getString(R.string.marked_as_done))
                    } else {
                        tts.speak(context.getString(R.string.skipped_for_later))
                    }
                }
                instruction.contains("done") -> {
                    repository.markReadAloud(message.id)
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

    /** Returns true only if a reply was actually captured and sent — callers decide skip vs done from that. */
    private suspend fun handleSpokenReply(accessibility: MessageAccessibilityService, app: SourceApp): Boolean {
        tts.speak(context.getString(R.string.prompt_say_reply))
        val replyText = stt.listenOnce(timeoutMs = REPLY_CAPTURE_TIMEOUT_MS)
        if (replyText.isNullOrBlank()) {
            Log.d(TAG, "handleSpokenReply: no reply captured within timeout")
            return false
        }

        val sent = accessibility.sendReply(app, replyText)
        Log.d(TAG, "handleSpokenReply: app=${app.name} sendReply result=$sent")
        return sent
    }

    private suspend fun runSearch(app: SourceApp?, keywords: String) {
        val accessibility = MessageAccessibilityService.currentInstance()
        if (accessibility == null) {
            tts.speak(context.getString(R.string.status_accessibility_missing))
            _status.value = context.getString(R.string.status_accessibility_missing)
            return
        }

        val targets = app?.let { listOf(it) } ?: SourceApp.entries.toList()
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

package com.voiceaccess.messenger.controller

import android.content.Context
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

    /** "Read my messages": queries all unread rows across both apps, reads each aloud, marks it read. */
    fun readUnreadMessages() {
        if (activeJob?.isActive == true) return
        activeJob = scope.launch { runReadUnreadMessages() }
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
            lower.contains("read") && lower.contains("messag") -> readUnreadMessages()
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

    private suspend fun runReadUnreadMessages() {
        val unread = repository.getUnread()
        if (unread.isEmpty()) {
            tts.speak(context.getString(R.string.no_unread_messages))
            _status.value = context.getString(R.string.status_ready)
            return
        }

        val accessibility = MessageAccessibilityService.currentInstance()
        for (message in unread) {
            _status.value = "Reading ${message.sourceApp.displayName} message from ${message.sender}…"

            // Full-content scrape is best-effort: if the accessibility
            // service isn't enabled or the scrape fails/times out, fall back
            // to the (possibly truncated) notification preview rather than
            // silently skipping the message.
            val body = accessibility?.openAndReadMessage(message)?.takeIf { it.isNotBlank() }
                ?: message.previewText

            tts.speak("New ${message.sourceApp.displayName} message from ${message.sender}. $body")
            repository.markReadAloud(message.id)

            if (accessibility == null) continue

            tts.speak(context.getString(R.string.prompt_reply_or_next))
            val instruction = stt.listenOnce(timeoutMs = 6_000)?.lowercase()
            when {
                instruction == null -> continue
                instruction.contains("reply") -> handleSpokenReply(accessibility, message.sourceApp)
                instruction.contains("stop") || instruction.contains("done") -> break
                else -> continue // "skip"/"next"/anything unrecognized: move on
            }
        }
        _status.value = context.getString(R.string.status_ready)
    }

    private suspend fun handleSpokenReply(accessibility: MessageAccessibilityService, app: SourceApp) {
        tts.speak(context.getString(R.string.prompt_say_reply))
        val replyText = stt.listenOnce(timeoutMs = 12_000)
        if (replyText.isNullOrBlank()) return

        val sent = accessibility.sendReply(app, replyText)
        tts.speak(context.getString(if (sent) R.string.reply_sent else R.string.reply_failed))
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
}
